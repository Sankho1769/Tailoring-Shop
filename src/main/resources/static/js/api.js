const API = {
  cookie(name) { const part = document.cookie.split('; ').find(x => x.startsWith(name + '=')); return part ? decodeURIComponent(part.split('=').slice(1).join('=')) : ''; },
  async csrf() { const r = await fetch('/api/auth/csrf', { credentials: 'same-origin' }); if (!r.ok) throw new Error('Could not initialize session security'); return r.json(); },
  async request(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    if (!['GET','HEAD','OPTIONS','TRACE'].includes(method)) {
      if (!this.cookie('XSRF-TOKEN')) await this.csrf();
      headers.set('X-XSRF-TOKEN', this.cookie('XSRF-TOKEN'));
    }
    const response = await fetch(path, {...options, method, headers, credentials: 'same-origin'});
    if (response.status === 204) return null;
    const contentType = response.headers.get('content-type') || '';
    const body = contentType.includes('json') ? await response.json() : await response.text();
    if (!response.ok) throw new Error(body?.message || body?.detail || body?.error || (typeof body === 'string' ? body : `Request failed (${response.status})`));
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
window.setNotice = (selector, message, ok=false) => { const el=document.querySelector(selector); if(el){el.textContent=message;el.className='notice '+(ok?'success':'error');el.hidden=false;} };

window.whatsappDigits = value => { let digits=String(value||'').replace(/\D/g,''); if(digits.length===10) return '91'+digits; if(digits.length===11 && digits.startsWith('0')) return '91'+digits.slice(1); return digits; };
