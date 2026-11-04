<script>
  let { theme, icon } = $props()
</script>

  <div class="theme-control">
    <button class="place theme-button" class:on={theme.menu} aria-label="Тема" title={theme.mode === 'system' ? 'Тема: как в системе' : theme.dark ? 'Тёмная тема' : 'Светлая тема'} aria-expanded={theme.menu} onclick={theme.toggle}>{@render icon(theme.mode === 'system' ? 'monitor' : theme.dark ? 'moon' : 'sun', 18)}</button>
    {#if theme.menu}
      <div class="theme-options" role="group" aria-label="Тема" style:top="{theme.top}px" style:left="{theme.left}px">
        {#each [{ key: 'system', name: 'Как в системе', icon: 'monitor' }, { key: 'light', name: 'Светлая тема', icon: 'sun' }, { key: 'dark', name: 'Тёмная тема', icon: 'moon' }] as mode}
          <button class:on={theme.mode === mode.key} aria-pressed={theme.mode === mode.key} onclick={() => theme.select(mode.key)}>{@render icon(mode.icon, 18)}<span>{mode.name}</span></button>
        {/each}
      </div>
    {/if}
  </div>
