// Echo's relay between the phone and a browser on a computer: a blind pipe, nothing more.
//
// The browser waits on a room (GET /v1/wait/{room}); the phone sends into it (POST /v1/send/{room}); the bytes stream from one
// request into the other and are gone. The relay never sees a key (the browser shows it in a QR code, the phone reads it with its
// camera), keeps nothing on disk, has no database and forgets a room as soon as nobody waits in it.
package main

import (
	"flag"
	"log"
	"net/http"
	"time"
)

func main() {
	addr := flag.String("addr", "127.0.0.1:8787", "where to listen (behind nginx, which holds TLS)")
	flag.Parse()
	r := NewRelay(DefaultLimits())
	srv := &http.Server{
		Addr:              *addr,
		Handler:           r,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}
	log.Printf("echo relay on %s", *addr)
	log.Fatal(srv.ListenAndServe())
}
