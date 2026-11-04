<script>
  let { stage, qrSvg, debugPairing, endedBy, link, onStart, onResume, icon } = $props()
</script>

{#if stage === 'camera'}
  <main class="center">
    <div class="card pair">
      {@render icon('phone', 32)}
      <h1>Этот код читает Эхо</h1>
      <p>Откройте Эхо, меню → «На компьютере», и наведите камеру на код ещё раз.</p>
    </div>
  </main>
{:else if stage === 'qr'}
  <main class="center">
    <div class="card pair">
      <div class="brand">Говорун Эхо</div>
      <div class="qr" aria-label="Код для Эхо" data-pairing={debugPairing || undefined}>{@html qrSvg}</div>
      <h1>Наведите Эхо на код</h1>
      {#if link === 'paused'}<button class="filled" onclick={onResume}>Продолжить подключение</button>{/if}
      <p>В Эхо: меню → «На компьютере». Обновление страницы сохраняет это окно. «Завершить сеанс» удаляет данные вкладки.</p>
      <div class="quiet">
        {@render icon('lock', 16)}
        {#if link === 'offline'}Нет связи с сервером, пробую снова{:else}Без входа и без облака, сервер заметок не видит{/if}
      </div>
    </div>
  </main>
{:else if stage === 'ended'}
  <main class="center">
    <div class="card pair">
      {@render icon('lock', 32)}
      <h1>Вкладка всё забыла</h1>
      <p>
        {#if endedBy === 'phone'}Эхо на телефоне завершило сеанс.{:else}Вы завершили сеанс в этой вкладке.{/if}
      </p>
      <button class="filled" onclick={onStart}>Новый код</button>
    </div>
  </main>
{/if}
