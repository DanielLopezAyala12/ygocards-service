/*
 * Frontend for ygocards-service.
 *
 * Two things here are deliberate rather than incidental.
 *
 * 1. No image URL is ever constructed from an upstream host. Every one comes from the `image`
 *    object the API returns, which always points back at this service. The provider prohibits
 *    hotlinking its image host, and the only reliable way to honour that is for the browser
 *    never to learn the host exists.
 *
 * 2. The four error statuses are shown differently, because they mean different things. The 503
 *    in particular is not presented as a failure: it means this service is holding outbound
 *    traffic under the provider's limit, nothing is broken, and the caller should wait.
 */

const form = document.getElementById('search-form');
const input = document.getElementById('query');
const submit = document.getElementById('submit');
const statusBox = document.getElementById('status');
const metaBox = document.getElementById('meta');
const metaCount = document.getElementById('meta-count');
const metaCache = document.getElementById('meta-cache');
const results = document.getElementById('results');

form.addEventListener('submit', (event) => {
  event.preventDefault();
  search(input.value.trim());
});

async function search(name) {
  if (!name) return;

  setBusy(true);
  hide(statusBox);
  hide(metaBox);
  results.innerHTML = '';

  try {
    const response = await fetch(`/api/cards?name=${encodeURIComponent(name)}&limit=24`);

    if (!response.ok) {
      showError(response, await safeJson(response));
      return;
    }

    const payload = await response.json();
    // The header carries the same fact as the body field. Reading the header is the honest
    // source: it is what a proxy or a load test would see.
    const cacheState = response.headers.get('X-Cache') || (payload.cache === 'hit' ? 'HIT' : 'MISS');
    render(payload, cacheState);

  } catch (networkError) {
    show(statusBox, 'status--error',
      'Could not reach the service',
      'The request never left the browser, or the service is not running.');
  } finally {
    setBusy(false);
  }
}

function render(payload, cacheState) {
  if (payload.count === 0) {
    show(statusBox, 'status--info',
      'No cards matched',
      `Nothing in the card database matches "${payload.query.name}". This is a successful search with an empty result, not an error.`);
    return;
  }

  metaCount.textContent = payload.totalRows && payload.totalRows > payload.count
    ? `Showing ${payload.count} of ${payload.totalRows} matches`
    : `${payload.count} ${payload.count === 1 ? 'match' : 'matches'}`;

  const hit = cacheState === 'HIT';
  metaCache.textContent = hit ? 'Served from this service’s cache' : 'Fetched from the card API';
  metaCache.className = `chip ${hit ? 'chip--hit' : 'chip--miss'}`;
  show(metaBox);

  const fragment = document.createDocumentFragment();
  for (const card of payload.cards) {
    fragment.appendChild(cardElement(card));
  }
  results.appendChild(fragment);
}

function cardElement(card) {
  const article = document.createElement('article');
  article.className = 'card';

  const img = document.createElement('img');
  img.className = 'card__media';
  // Straight from the API response. Nothing here knows the upstream image host.
  img.src = card.image.small;
  img.alt = card.name;
  img.loading = 'lazy';
  article.appendChild(img);

  const body = document.createElement('div');
  body.className = 'card__body';

  const title = document.createElement('h2');
  title.className = 'card__title';
  title.textContent = card.name;
  body.appendChild(title);

  const type = document.createElement('p');
  type.className = 'card__type';
  type.textContent = [card.type, card.race].filter(Boolean).join(' · ');
  body.appendChild(type);

  const stats = statChips(card);
  if (stats) body.appendChild(stats);

  if (card.description) {
    const desc = document.createElement('p');
    desc.className = 'card__desc';
    desc.textContent = card.description;
    body.appendChild(desc);
  }

  article.appendChild(body);
  return article;
}

/** Only the stats that apply. A spell card has no ATK, and showing "ATK null" would be noise. */
function statChips(card) {
  const values = [];
  if (card.attribute) values.push(card.attribute);
  if (card.level !== null && card.level !== undefined) values.push(`Level ${card.level}`);
  if (card.atk !== null && card.atk !== undefined) values.push(`ATK ${card.atk}`);
  if (card.def !== null && card.def !== undefined) values.push(`DEF ${card.def}`);
  if (values.length === 0) return null;

  const wrap = document.createElement('p');
  wrap.className = 'card__stats';
  for (const value of values) {
    const chip = document.createElement('span');
    chip.className = 'stat';
    chip.textContent = value;
    wrap.appendChild(chip);
  }
  return wrap;
}

function showError(response, body) {
  const detail = body && body.message ? body.message : '';

  switch (response.status) {
    case 400:
      show(statusBox, 'status--error', 'That search cannot be run', detail);
      break;

    case 503: {
      // Not a provider failure. This service is keeping its own outbound traffic under the
      // limit the provider publishes, and going over it would get us blocked for an hour.
      const retryAfter = response.headers.get('Retry-After') || (body && body.retryAfterSeconds) || 1;
      show(statusBox, 'status--wait',
        'Holding requests under the provider’s rate limit',
        `Nothing is broken. This service limits how fast it calls the card API on purpose. Try again in ${retryAfter} second${retryAfter === 1 || retryAfter === '1' ? '' : 's'}.`);
      break;
    }

    case 504:
      show(statusBox, 'status--error',
        'The card API did not answer in time',
        'The request reached the provider but no response arrived inside the timeout. This often clears by itself.');
      break;

    case 502:
      show(statusBox, 'status--error',
        'The card API could not be reached',
        'The provider is unreachable or answered with something this service cannot use.');
      break;

    default:
      show(statusBox, 'status--error', `Unexpected response (${response.status})`, detail);
  }
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch (e) {
    return null;
  }
}

function show(element, className, title, detail) {
  if (className) {
    element.className = `status ${className}`;
    element.innerHTML = '';
    const h = document.createElement('p');
    h.className = 'status__title';
    h.textContent = title;
    element.appendChild(h);
    if (detail) {
      const p = document.createElement('p');
      p.className = 'status__detail';
      p.textContent = detail;
      element.appendChild(p);
    }
  }
  element.hidden = false;
}

function hide(element) {
  element.hidden = true;
}

function setBusy(busy) {
  submit.disabled = busy;
  submit.textContent = busy ? 'Searching…' : 'Search';
}
