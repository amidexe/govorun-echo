<script>
  let { raw, plain, count = 1, fileRaw = raw, filePlain = plain,
    open, onToggle, onSave, onCopy, icon } = $props()
</script>

  <div class="actions">
    <div class="export-menu">
      <button class="ghost" aria-label="Другие действия" aria-expanded={open} onclick={onToggle}>{@render icon('more', 20)}</button>
      {#if open}
        <div class="export-options">
          <button onclick={() => onSave(filePlain)} disabled={!filePlain}>{@render icon('download', 18)}Сохранить текст (.txt)</button>
          <button onclick={() => onCopy(raw, count)} disabled={!raw}>{@render icon('copy', 18)}Скопировать Markdown</button>
          <button onclick={() => onSave(fileRaw, true)} disabled={!fileRaw}>{@render icon('download', 18)}Сохранить Markdown (.md)</button>
        </div>
      {/if}
    </div>
    <button class="filled" onclick={() => onCopy(plain, count)} disabled={!plain}>{@render icon('copy', 18)}<span>Скопировать</span></button>
  </div>
