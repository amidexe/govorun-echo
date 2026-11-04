<script>
  import { renderMarkdown } from './markdown.js'
  let { note, layer, metadata, markdown, words, marked } = $props()
</script>

<div class="reader">
  {#if metadata.title}<h2>{@render marked(metadata.title)}</h2>{/if}
  {#if metadata.about}<div class="about">{@render marked(metadata.about)}</div>{/if}
  {#if markdown}
    <div class="markdown">{@html renderMarkdown(layer.text || '', words)}</div>
  {:else}
    <p>{@render marked(layer.text || '')}</p>
  {/if}
  {#if layer.fresh === false}<div class="hint">Запись изменилась после сохранения этой версии.</div>{/if}
  {#if layer.cut}<div class="about">Заметка очень длинная: здесь её начало, целиком — в телефоне.</div>{/if}
  {#if note.tags?.length}<div class="note-tags">{note.tags.join(' · ')}</div>{/if}
</div>
