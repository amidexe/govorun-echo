<script>
  import { when, rowWhen, length } from './display.js'
  import { titleOf } from './note-content.js'
  let { query = $bindable(), element = $bindable(), scope, tag, pickedCount,
    shown, drawn, chosen, openId, fresh, preview, marked, icon, more,
    onSearch, onEnterPane, onClear, onToggle, onShow } = $props()
</script>

<section class="list" bind:this={element} aria-label="Список заметок">
  <label class="search">
    {@render icon('search', 18)}
    <input placeholder={scope === 'archive' ? 'Поиск в архиве' : scope === 'favorites' ? 'Поиск в избранном' : 'Поиск'} bind:value={query} oninput={onSearch} />
    {#if query}<button class="bare" onclick={() => (query = '')} aria-label="Очистить">{@render icon('close', 16)}</button>{/if}
  </label>
  {#if pickedCount}
    <div class="compact-selection">
      <button class="filled" onclick={onEnterPane}>Открыть выбранное ({pickedCount})</button>
      <button class="bare" onclick={onClear} aria-label="Снять выбор">{@render icon('close', 18)}</button>
    </div>
  {/if}
  {#if shown.length}
    <div class="group">
      {#each shown.slice(0, drawn) as n (n.id)}
        <div class="note" class:chosen={chosen.has(n.id)} class:opened={openId === n.id && !chosen.size}>
          <button class="mark" class:on={chosen.has(n.id)} onclick={() => onToggle(n.id)} aria-label="Выбрать">
            {#if chosen.has(n.id)}{@render icon('check', 14)}{/if}
          </button>
          <button class="body" onclick={() => onShow(n.id)}>
            <span class="meta">
              <span title={when(n.at)}>{rowWhen(n.at)}</span>{#if n.dur}<span>{length(n.dur, true)}</span>{/if}
              {#if n.pinned}<span aria-label="Избранное" title="Избранное">{@render icon('star', 14)}</span>{/if}
              {#if n.archived && scope !== 'archive'}<span aria-label="В архиве" title="В архиве">{@render icon('archive', 14)}</span>{/if}
              {#if fresh.has(n.id)}<span class="new">новая</span>{/if}
            </span>
            {#if titleOf(n)}<span class="title">{@render marked(titleOf(n))}</span>{/if}
            <span class="text">{@render marked(preview(n))}</span>
            {#if n.tags?.length}<span class="note-tags">{n.tags.join(' · ')}</span>{/if}
          </button>
        </div>
      {/each}
    </div>
  {:else}
    <p class="empty">{query || tag ? 'Ничего не нашлось' : scope === 'archive' ? 'В архиве пусто' : scope === 'favorites' ? 'В избранном пусто' : 'Заметок нет'}</p>
  {/if}
  {#if drawn < shown.length}<div class="more" use:more>{shown.length - drawn} ещё…</div>{/if}
</section>
