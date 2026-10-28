let csrfTokenCache = null;

const API = {
  cookie(name) {
    const part = document.cookie.split('; ').find(x => x.startsWith(name + '='));
    return part ? decodeURIComponent(part.split('=').slice(1).join('=')) : '';
  },
  async csrf() {
    try {
      const r = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
      if (!r.ok) throw new Error('Could not initialize session security');
      const data = await r.json();
      if (data && data.token) {
        csrfTokenCache = data.token;
      }
      return data;
    } catch (err) {
      console.warn('Failed to retrieve CSRF token:', err);
      throw err;
    }
  },
  async request(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    if (!['GET','HEAD','OPTIONS','TRACE'].includes(method)) {
      let token = csrfTokenCache || this.cookie('XSRF-TOKEN');
      if (!token) {
        try {
          const c = await this.csrf();
          token = c?.token || this.cookie('XSRF-TOKEN');
        } catch (_) {}
      }
      if (token) {
        headers.set('X-XSRF-TOKEN', token);
        headers.set('X-CSRF-TOKEN', token);
      }
    }
    const response = await fetch(path, {...options, method, headers, credentials: 'same-origin'});
    if (response.status === 204) return null;
    const contentType = response.headers.get('content-type') || '';
    const body = contentType.includes('json') ? await response.json() : await response.text();
    if (!response.ok) {
      const msg = body?.message || body?.detail || body?.error || (typeof body === 'string' ? body : `Request failed (${response.status})`);
      throw new Error(msg);
    }
    return body;
  },
  get(path) { return this.request(path); },
  post(path, data) { return this.request(path, {method:'POST', body: data instanceof FormData ? data : JSON.stringify(data)}); },
  patch(path, data) { return this.request(path, {method:'PATCH', body:JSON.stringify(data)}); },
  delete(path) { return this.request(path, {method:'DELETE'}); }
};

window.API = API;
window.escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
window.money = value => Number(value || 0).toLocaleString('en-IN',{style:'currency',currency:'INR',minimumFractionDigits:2});
window.setNotice = (selector, message, ok=false) => {
  const el = document.querySelector(selector);
  if (el) {
    el.textContent = message;
    el.className = 'notice ' + (ok ? 'success' : 'error');
    el.hidden = false;
  }
};
window.whatsappDigits = value => {
  let digits = String(value || '').replace(/\D/g,'');
  if (digits.length === 10) return '91' + digits;
  if (digits.length === 11 && digits.startsWith('0')) return '91' + digits.slice(1);
  return digits;
};
