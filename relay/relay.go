package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Limits keep one person from taking the relay from everyone else.
type Limits struct {
	MaxBody     int64         // one message (all the notes at once), already encrypted
	MaxRooms    int           // rooms open at once, over everyone
	MaxActive   int           // global in-flight requests, independent of source addresses
	MaxPerIP    int           // requests open at once from one address
	Wait        time.Duration // how long a browser waits before asking again
	SendWait    time.Duration // how long the phone waits for the browser to come back between two waits
	StreamLimit time.Duration // the most one message may take to pass through
}

func DefaultLimits() Limits {
	return Limits{
		MaxBody:     8 << 20, // a part of the notes is about 1 MB after gzip (the phone splits at 3 MB of JSON)
		MaxRooms:    1000,    // bounded active rooms; verified separately from global request and memory limits
		MaxActive:   2048,
		MaxPerIP:    64, // an office or a university behind one address
		Wait:        50 * time.Second,
		SendWait:    15 * time.Second,
		StreamLimit: 5 * time.Minute,
	}
}

// A room's name is the first 22 base64url characters of SHA-256 over the browser's waiting secret (16 random bytes, never in the
// code on the screen): anyone who saw the code can send into the room, only the tab that made it can wait in it.
var roomName = regexp.MustCompile(`^[A-Za-z0-9_-]{22}$`)

func mayWait(room, secret string) bool {
	raw, err := base64.RawURLEncoding.DecodeString(secret)
	if err != nil || len(raw) != 16 {
		return false
	}
	sum := sha256.Sum256(raw)
	return subtle.ConstantTimeCompare([]byte(base64.RawURLEncoding.EncodeToString(sum[:])[:22]), []byte(room)) == 1
}

type delivery struct {
	body   io.Reader
	size   int64
	id     string
	done   chan error
	cancel func()
}

type room struct {
	ch       chan *delivery
	refs     int
	waiting  bool
	receipts map[string]chan []byte
}

type Relay struct {
	lim    Limits
	mu     sync.Mutex
	rooms  map[string]*room
	perIP  map[string]int
	active int
}

func NewRelay(lim Limits) *Relay {
	return &Relay{lim: lim, rooms: map[string]*room{}, perIP: map[string]int{}}
}

// Rooms says how many rooms are open now, for tests.
func (r *Relay) Rooms() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.rooms)
}

func (r *Relay) ServeHTTP(w http.ResponseWriter, req *http.Request) {
	h := w.Header()
	h.Set("Cache-Control", "no-store")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	if req.URL.Path == "/v1/health" {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	verb, name, ok := strings.Cut(strings.TrimPrefix(req.URL.Path, "/v1/"), "/")
	var deliveryID string
	if ok && verb == "receipt" {
		name, deliveryID, ok = strings.Cut(name, "/")
		if !roomName.MatchString(deliveryID) {
			ok = false
		}
	}
	if !ok || !roomName.MatchString(name) {
		http.NotFound(w, req)
		return
	}
	ip := clientIP(req)
	if !r.enter(ip) {
		http.Error(w, "busy", http.StatusTooManyRequests)
		return
	}
	defer r.leave(ip)
	switch {
	case verb == "wait" && req.Method == http.MethodGet:
		r.wait(w, req, name)
	case verb == "send" && req.Method == http.MethodPost:
		r.send(w, req, name)
	case verb == "receipt" && req.Method == http.MethodPost:
		r.receipt(w, req, name, deliveryID)
	default:
		http.Error(w, "method", http.StatusMethodNotAllowed)
	}
}

// The browser waits for the next message; with none in [Limits.Wait] it gets 204 and asks again.
func (r *Relay) wait(w http.ResponseWriter, req *http.Request, name string) {
	if !mayWait(name, req.Header.Get("X-Echo-Wait")) {
		http.Error(w, "not yours", http.StatusForbidden)
		return
	}
	rm, ok := r.open(name, true)
	if !ok {
		http.Error(w, "busy", http.StatusConflict)
		return
	}
	defer r.close(name, true)
	timer := time.NewTimer(r.lim.Wait)
	defer timer.Stop()
	select {
	case <-req.Context().Done():
		return
	case <-timer.C:
		w.WriteHeader(http.StatusNoContent)
		return
	case d := <-rm.ch:
		stop := context.AfterFunc(req.Context(), d.cancel)
		defer stop()
		rc := http.NewResponseController(w)
		_ = rc.SetWriteDeadline(time.Now().Add(r.lim.StreamLimit))
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("X-Echo-Delivery", d.id)
		if d.size >= 0 {
			w.Header().Set("Content-Length", strconv.FormatInt(d.size, 10))
		}
		w.WriteHeader(http.StatusOK)
		buf := make([]byte, 32<<10)
		_, err := io.CopyBuffer(w, d.body, buf)
		d.done <- err
	}
}

// The phone sends one message; it passes straight into the waiting browser and is kept nowhere.
func (r *Relay) send(w http.ResponseWriter, req *http.Request, name string) {
	if req.ContentLength > r.lim.MaxBody {
		http.Error(w, "too large", http.StatusRequestEntityTooLarge)
		return
	}
	rm, _ := r.open(name, false)
	if rm == nil {
		http.Error(w, "busy", http.StatusServiceUnavailable)
		return
	}
	defer r.close(name, false)
	id := req.Header.Get("X-Echo-Delivery")
	var accepted chan []byte
	if !roomName.MatchString(id) {
		http.Error(w, "delivery required", http.StatusBadRequest)
		return
	}
	{
		if !roomName.MatchString(id) {
			http.Error(w, "delivery", http.StatusBadRequest)
			return
		}
		r.mu.Lock()
		if rm.receipts == nil {
			rm.receipts = make(map[string]chan []byte)
		}
		if len(rm.receipts) != 0 {
			r.mu.Unlock()
			http.Error(w, "busy", http.StatusConflict)
			return
		}
		accepted = make(chan []byte, 1)
		rm.receipts[id] = accepted
		r.mu.Unlock()
		defer func() { r.mu.Lock(); delete(rm.receipts, id); r.mu.Unlock() }()
	}
	rc := http.NewResponseController(w)
	_ = rc.SetReadDeadline(time.Now().Add(r.lim.StreamLimit))
	d := &delivery{body: http.MaxBytesReader(w, req.Body, r.lim.MaxBody), size: req.ContentLength, id: id, done: make(chan error, 1), cancel: func() { _ = rc.SetReadDeadline(time.Now()) }}
	timer := time.NewTimer(r.lim.SendWait)
	defer timer.Stop()
	select {
	case <-req.Context().Done():
		return
	case <-timer.C:
		// Nobody waits: the tab was closed or the computer went to sleep.
		http.Error(w, "no one waits", http.StatusGone)
		return
	case rm.ch <- d:
	}
	select {
	case err := <-d.done:
		if err != nil {
			http.Error(w, "broken", http.StatusBadGateway)
			return
		}
	case <-req.Context().Done():
		http.Error(w, "cancelled", http.StatusBadGateway)
		return
	}
	{
		timer := time.NewTimer(10 * time.Second)
		defer timer.Stop()
		select {
		case receipt := <-accepted:
			w.Header().Set("Content-Type", "application/octet-stream")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(receipt)
		case <-req.Context().Done():
		case <-timer.C:
			http.Error(w, "not accepted", http.StatusGatewayTimeout)
		}
		return
	}
}

// Receipt is opaque and bounded. It exists only for the duration of the phone's own POST.
func (r *Relay) receipt(w http.ResponseWriter, req *http.Request, name, id string) {
	if !mayWait(name, req.Header.Get("X-Echo-Wait")) {
		http.Error(w, "not yours", http.StatusForbidden)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, req.Body, 32))
	if err != nil || len(body) != 32 {
		http.Error(w, "receipt", http.StatusBadRequest)
		return
	}
	r.mu.Lock()
	var accepted chan []byte
	if rm := r.rooms[name]; rm != nil {
		accepted = rm.receipts[id]
	}
	r.mu.Unlock()
	if accepted == nil {
		http.Error(w, "expired", http.StatusGone)
		return
	}
	select {
	case accepted <- body:
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "duplicate", http.StatusConflict)
	}
}

// open takes the room, making it when it is new; a second browser in the same room is refused.
func (r *Relay) open(name string, waiter bool) (*room, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	rm := r.rooms[name]
	if rm == nil {
		if len(r.rooms) >= r.lim.MaxRooms {
			return nil, false
		}
		rm = &room{ch: make(chan *delivery)}
		r.rooms[name] = rm
	}
	if waiter {
		if rm.waiting {
			return nil, false
		}
		rm.waiting = true
	}
	rm.refs++
	return rm, true
}

func (r *Relay) close(name string, waiter bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	rm := r.rooms[name]
	if rm == nil {
		return
	}
	if waiter {
		rm.waiting = false
	}
	rm.refs--
	if rm.refs <= 0 {
		delete(r.rooms, name)
	}
}

func (r *Relay) enter(ip string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.perIP[ip] >= r.lim.MaxPerIP || (r.lim.MaxActive > 0 && r.active >= r.lim.MaxActive) {
		return false
	}
	r.perIP[ip]++
	r.active++
	return true
}

func (r *Relay) leave(ip string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.active--
	if r.perIP[ip]--; r.perIP[ip] <= 0 {
		delete(r.perIP, ip)
	}
}

// clientIP is the caller's address; behind our own nginx on the same machine it is the one nginx passes on.
func clientIP(req *http.Request) string {
	host, _, err := net.SplitHostPort(req.RemoteAddr)
	if err != nil {
		host = req.RemoteAddr
	}
	if host == "127.0.0.1" || host == "::1" {
		if real := req.Header.Get("X-Real-IP"); real != "" {
			return real
		}
	}
	return host
}
