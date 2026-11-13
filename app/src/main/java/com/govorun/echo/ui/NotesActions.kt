package com.govorun.echo.ui

/** Everything the list of notes and a note's own screen can ask of the app; [MainActivity] supplies it. */
internal class NotesActions(
    val onCardClick: (String) -> Unit,
    val onCardLongClick: (String) -> Unit,
    val onRecord: () -> Unit,
    /** «+» held: a new note of text only, opened to type into. */
    val onNewText: () -> Unit = {},
    val onStop: () -> Unit,
    /** Pauses the running recording (true) or goes on with it (false), as the notification does. */
    val onPauseRecording: (Boolean) -> Unit,
    val onSettings: () -> Unit,
    val onHelp: () -> Unit,
    val onTrash: () -> Unit,
    /**
     * A card shows its name and what the note is about instead of its first lines, while the smart functions are on (and its switch in
     * Settings → «Умные» is); tags, filters, search and swipes work as always.
     */
    val trashCount: Int,
    val onToggleSearch: () -> Unit,
    val onTab: (NotesTab) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onOpenFilters: () -> Unit,
    val onFilter: (NotesFilter) -> Unit,
    val onClearSelection: () -> Unit,
    val onSetSeen: (List<String>, Boolean) -> Unit = { _, _ -> },
    val onSetFavorites: (List<String>, Boolean) -> Unit = { _, _ -> },
    val onCopy: (List<String>) -> Unit,
    val onShare: (List<String>) -> Unit,
    val onMerge: (List<String>) -> Unit,
    val onSplit: (String) -> Unit,
    val onDelete: (List<String>) -> Unit,
    val onRetry: (String) -> Unit,
    /** Stops recognizing a note for now: its sound and what was heard stay. */
    val onPostpone: (String) -> Unit = {},
    /** The system's file picker for audio and video, several at once. */
    val onAddFiles: () -> Unit = {},
    val onSetTag: (List<String>, String, Boolean) -> Unit,
    /** The note's name as typed on its screen; blank takes the name away. */
    val onSetTitle: (String, String) -> Unit,
    val onTagFilter: (String?) -> Unit,
    val onNewTag: (String, Int) -> Unit,
    val onDeleteTag: (String) -> Unit,
    /** Every tag: the pinned ones in the order of the row first, then the recently used. */
    val allTags: List<String>,
    val madeTags: Set<String>,
    val tagStyles: Map<String, TagStyle>,
    val onTagStyle: (String, TagStyle) -> Unit,
    /** The row above the list: the pinned tags in its order; the rest are found under «#». */
    val pinnedTags: List<String>,
    /** How many notes carry each tag, archived ones too. */
    val tagUse: Map<String, Int>,
    /** The row's order after a drag, of the pills it showed. */
    val onTagOrder: (List<String>) -> Unit,
    val onPinTag: (String, Boolean) -> Unit,
    val onRenameTag: (String, String, String?) -> Unit,
    val onPlay: (String) -> Unit,
    val onSeek: (Long) -> Unit,
    val onPlayFrom: (String, Long) -> Unit,
    val onAppend: (String) -> Unit,
    val onDeleteAudio: (String) -> Unit,
    val onArchive: (List<String>, Boolean) -> Unit,
    val onExport: (List<String>) -> Unit,
    val onRequestMicrophone: () -> Unit,
    /** A note's files changed outside the actions above (its «Кратко» was kept): the list is read again. */
    val onChanged: () -> Unit = {},
    val onAuthorizeGovorun: () -> Unit,
    val onGetGovorun: () -> Unit,
    /** Plays a draft of «Фрагмент»: stretches of the note's own sound one after another, from a moment of the draft, the draft's length. */
    val onPlayPieces: (String, List<Pair<Long, Long>>, Long, Long) -> Unit = { _, _, _, _ -> },
    /** «Фрагмент» and fixing words: what a note's screen changes on disk. */
    val edits: NoteEdits = NoteEdits.None,
)
