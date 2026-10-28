const measurementDefinitions = {
  'Blouse': ['shoulder','bust','upperBust','blouseWaist','blouseLength','sleeveLength','armhole','biceps','wrist','frontNeck','backNeck','apexDistance'],
  'Salwar / Kameez': ['shoulder','bust','waist','hip','kameezLength','sleeveLength','armhole','neckDepth','salwarLength','thigh','ankle'],
  'Pant': ['waist','hip','rise','thigh','knee','bottom','inseam','outseam','pantLength'],
  'Frock': ['shoulder','bust','waist','hip','armhole','sleeveLength','frockLength','neckDepth'],
  'Lehenga': ['waist','hip','lehengaLength','flare','waistBand'],
  'Kurti': ['shoulder','bust','waist','hip','armhole','sleeveLength','kurtiLength','neckDepth'],
  'False Par & Peko': ['sareeLength','sareeWidth','parLength','pekoEdges','notesMeasurement'],
  'Alteration': ['shoulder','bust','waist','hip','sleeveLength','lengthToAdjust','waistToAdjust','notesMeasurement'],
  'General body measurements': ['shoulder','bust','upperBust','waist','hip','armhole','biceps','wrist','fullLength','inseam']
};

const labels = {
  shoulder: 'Shoulder', bust: 'Bust / Chest', upperBust: 'Upper bust', blouseWaist: 'Blouse waist',
  waist: 'Waist', hip: 'Hip', blouseLength: 'Blouse length', sleeveLength: 'Sleeve length',
  armhole: 'Armhole', biceps: 'Biceps / Upper arm', wrist: 'Wrist', frontNeck: 'Front neck depth',
  backNeck: 'Back neck depth', apexDistance: 'Apex distance', kameezLength: 'Kameez length',
  neckDepth: 'Neck depth', salwarLength: 'Salwar length', thigh: 'Thigh', ankle: 'Ankle opening',
  rise: 'Rise / crotch', knee: 'Knee', bottom: 'Bottom opening', inseam: 'Inseam', outseam: 'Outseam',
  pantLength: 'Pant length', frockLength: 'Frock length', lehengaLength: 'Lehenga length', flare: 'Lehenga flare',
  waistBand: 'Waistband', kurtiLength: 'Kurti length', fullLength: 'Full body length', lengthToAdjust: 'Length to adjust',
  waistToAdjust: 'Waist adjustment', sareeLength: 'Saree length', sareeWidth: 'Saree width', parLength: 'Par length',
  pekoEdges: 'Peko edges', notesMeasurement: 'Other measurement'
};

const ORDER_STATUSES = [
  { value: 'NEW', label: 'New' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'TRIAL', label: 'Trial' },
  { value: 'READY', label: 'Ready' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'CANCELLED', label: 'Cancelled' }
];

let currentCustomer = null;
let currentMeasurement = null;
let products = [];
let cart = [];
let orders = [];
let shopSettings = {};

const val = id => {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
};

function drawMeasurementFields() {
  const garment = val('garment');
  const keys = measurementDefinitions[garment] || measurementDefinitions['General body measurements'];
  const unit = val('unit') === 'in' ? 'Inches' : 'cm';
  document.getElementById('measurementFields').innerHTML = keys.map(k => `
    <div class="field">
      <label for="m_${k}">${escapeHTML(labels[k] || k)}</label>
      <input id="m_${k}" data-measure="${k}" type="text" inputmode="decimal" placeholder="${unit}">
    </div>
  `).join('');
}

function readDimensions() {
  const result = {};
  document.querySelectorAll('[data-measure]').forEach(el => {
    if (el.value.trim() !== '') {
      result[el.dataset.measure] = el.value.trim();
    }
  });
  return result;
}

async function doLogin(event) {
  event.preventDefault();
  try {
    await API.csrf();
    const who = await API.post('/api/auth/login', { username: val('username'), password: val('password') });
    if (!['ADMIN', 'TAILOR'].includes(who.role)) {
      throw new Error('This account does not have tailor workspace permissions');
    }
    document.getElementById('whoami').textContent = `${who.username} · ${who.role}`;
    document.getElementById('loginPanel').classList.add('hidden');
    document.getElementById('workspace').classList.remove('hidden');
    document.getElementById('logout').classList.remove('hidden');
    await loadInitial();
  } catch (e) {
    setNotice('#loginNotice', e.message);
  }
}

async function loadCustomers(q = '') {
  try {
    const list = await API.get('/api/tailor/customers' + (q ? '?q=' + encodeURIComponent(q) : ''));
    const sel = document.getElementById('customerSelect');
    const previous = sel.value;
    sel.innerHTML = '<option value="">Select a customer…</option>' + list.map(c =>
      `<option value="${c.id}">${escapeHTML(c.name)} · ${escapeHTML(c.phone)}</option>`
    ).join('');
    if (previous && list.some(c => c.id === previous)) {
      sel.value = previous;
    }
  } catch (err) {
    setNotice('#customerNotice', err.message);
  }
}

async function chooseCustomer(id) {
  currentCustomer = null;
  currentMeasurement = null;
  drawMeasurementFields();
  document.getElementById('measureNotes').value = '';
  document.getElementById('fit').value = 'Regular';
  document.getElementById('measurementFieldset').disabled = !id;
  document.getElementById('measurementLock').hidden = !!id;
  document.getElementById('currentCustomer').hidden = true;
  document.getElementById('customerOrdersBox').classList.add('hidden');
  document.getElementById('measureHistory').innerHTML = '<tr><td colspan="4">Select a customer to view measurements.</td></tr>';

  if (!id) return;

  try {
    currentCustomer = await API.get('/api/tailor/customers/' + id);
    document.getElementById('cName').value = currentCustomer.name || '';
    document.getElementById('cPhone').value = currentCustomer.phone || '';
    document.getElementById('cAddress').value = currentCustomer.address || '';
    document.getElementById('cNotes').value = currentCustomer.notes || '';
    document.getElementById('currentCustomer').textContent = `Selected Customer: ${currentCustomer.name} · ${currentCustomer.phone}`;
    document.getElementById('currentCustomer').hidden = false;

    await Promise.all([
      loadMeasurementHistory(),
      loadCustomerOrdersHistory(currentCustomer.id)
    ]);
  } catch (e) {
    setNotice('#customerNotice', e.message);
  }
}

async function loadCustomerOrdersHistory(customerId) {
  try {
    const custOrders = orders.filter(o => o.customer_id === customerId);
    const box = document.getElementById('customerOrdersBox');
    const tbody = document.getElementById('customerOrdersHistory');
    if (!custOrders.length) {
      tbody.innerHTML = '<tr><td colspan="5">No previous orders for this customer.</td></tr>';
      box.classList.remove('hidden');
      return;
    }
    tbody.innerHTML = custOrders.map(o => `
      <tr>
        <td><strong>${escapeHTML(o.order_number)}</strong></td>
        <td>${escapeHTML(o.status)}</td>
        <td>${formatPaymentStatusBadge(o.payment_status)}</td>
        <td>${money(o.total)}</td>
        <td>${money(o.due)}</td>
      </tr>
    `).join('');
    box.classList.remove('hidden');
  } catch (e) {
    console.warn('Could not load customer order history', e);
  }
}

async function loadMeasurementHistory() {
  if (!currentCustomer) return;
  const rows = await API.get('/api/tailor/customers/' + currentCustomer.id + '/measurements');
  const body = document.getElementById('measureHistory');
  currentMeasurement = rows.length ? rows[0].id : null;

  body.innerHTML = rows.length ? rows.map(m => `
    <tr>
      <td><strong>${escapeHTML(m.garment_type)}</strong></td>
      <td>${escapeHTML(m.unit)}</td>
      <td>${escapeHTML(new Date(m.created_at).toLocaleDateString())}</td>
      <td>
        <button class="btn ghost" type="button" data-use-measurement="${m.id}">Select for Bill</button>
        <details style="margin-top:4px">
          <summary style="cursor:pointer;color:var(--violet)">View Dimensions</summary>
          <pre style="white-space:pre-wrap;font-size:12px;background:#f8f4f9;padding:6px;border-radius:6px;margin:4px 0">${escapeHTML(typeof m.dimensions === 'string' ? m.dimensions : JSON.stringify(m.dimensions, null, 2))}</pre>
          <small class="muted">${escapeHTML(m.fit_preference || '')} ${escapeHTML(m.notes || '')}</small>
        </details>
      </td>
    </tr>
  `).join('') : '<tr><td colspan="4">No measurements saved yet for this customer.</td></tr>';

  body.querySelectorAll('[data-use-measurement]').forEach(b => {
    b.addEventListener('click', () => {
      currentMeasurement = b.dataset.useMeasurement;
      setNotice('#orderNotice', 'Measurement snapshot linked to the bill.', true);
    });
  });
}

async function saveCustomer(e) {
  e.preventDefault();
  try {
    const payload = {
      name: val('cName'),
      phone: val('cPhone'),
      address: val('cAddress'),
      notes: val('cNotes')
    };
    const selectedId = document.getElementById('customerSelect').value;
    const saved = (currentCustomer && selectedId === currentCustomer.id)
      ? await API.patch('/api/tailor/customers/' + currentCustomer.id, payload)
      : await API.post('/api/tailor/customers', payload);

    currentCustomer = saved;
    currentMeasurement = null;
    document.getElementById('measurementFieldset').disabled = false;
    document.getElementById('measurementLock').hidden = true;
    drawMeasurementFields();
    document.getElementById('measureNotes').value = '';

    await loadCustomers();
    document.getElementById('customerSelect').value = saved.id;
    setNotice('#customerNotice', 'Customer record saved in PostgreSQL.', true);
    document.getElementById('currentCustomer').textContent = `Selected Customer: ${saved.name} · ${saved.phone}`;
    document.getElementById('currentCustomer').hidden = false;
    await loadMeasurementHistory();
  } catch (err) {
    setNotice('#customerNotice', err.message);
  }
}

async function saveMeasurement(e) {
  e.preventDefault();
  if (!currentCustomer) {
    setNotice('#measureNotice', 'Save or select a customer before entering measurements.');
    return;
  }
  try {
    const result = await API.post('/api/tailor/customers/' + currentCustomer.id + '/measurements', {
      garmentType: val('garment'),
      unit: val('unit'),
      dimensions: readDimensions(),
      fitPreference: val('fit'),
      notes: val('measureNotes')
    });
    currentMeasurement = result.id;
    setNotice('#measureNotice', 'Measurement snapshot saved in customer history.', true);
    await loadMeasurementHistory();
  } catch (err) {
    setNotice('#measureNotice', err.message);
  }
}

async function loadProducts() {
  products = await API.get('/api/public/products');
  const sel = document.getElementById('orderProduct');
  sel.innerHTML = products.map(p =>
    `<option value="${p.id}" data-name="${escapeHTML(p.name)}" data-rate="${p.default_rate}">
      ${escapeHTML(p.name)} · ${money(p.default_rate)}
    </option>`
  ).join('');
  if (products[0]) {
    document.getElementById('orderRate').value = products[0].default_rate;
  }
}

function updateRate() {
  const opt = document.querySelector('#orderProduct option:checked');
  if (opt) {
    document.getElementById('orderRate').value = opt.dataset.rate || 0;
  }
}

function addCartItem() {
  const p = products.find(p => p.id === val('orderProduct'));
  if (!p) {
    setNotice('#orderNotice', 'No active service selected. Please configure products in Admin.');
    return;
  }
  const qty = Number(val('orderQty'));
  const rate = Number(val('orderRate'));
  if (!Number.isInteger(qty) || qty < 1 || rate < 0 || !Number.isFinite(rate)) {
    setNotice('#orderNotice', 'Please verify service quantity and rate.');
    return;
  }
  cart.push({ productId: p.id, name: p.name, quantity: qty, rate });
  drawCart();
}

function drawCart() {
  const body = document.getElementById('cartRows');
  body.innerHTML = cart.length ? cart.map((x, i) => `
    <tr>
      <td><strong>${escapeHTML(x.name)}</strong></td>
      <td>${x.quantity}</td>
      <td>${money(x.rate)}</td>
      <td>${money(x.rate * x.quantity)}</td>
      <td><button class="btn ghost" type="button" data-remove-cart="${i}">Remove</button></td>
    </tr>
  `).join('') : '<tr><td colspan="5">No services added to bill yet.</td></tr>';

  body.querySelectorAll('[data-remove-cart]').forEach(b => {
    b.onclick = () => {
      cart.splice(Number(b.dataset.removeCart), 1);
      drawCart();
    };
  });

  const subtotal = cart.reduce((s, x) => s + x.rate * x.quantity, 0);
  const discount = Math.max(0, Number(val('discount') || 0));
  const total = Math.max(0, subtotal - discount);

  document.getElementById('cartSubtotal').textContent = money(subtotal);
  document.getElementById('cartTotal').textContent = money(total);
}

function formatPaymentStatusBadge(status) {
  const s = String(status || '').toUpperCase();
  switch (s) {
    case 'PAID':
      return '<span class="pill" style="background:#e3f7eb;color:#18643d">Paid</span>';
    case 'PARTIALLY_PAID':
      return '<span class="pill" style="background:#fff2d6;color:#995400">Partially Paid</span>';
    case 'REFUNDED':
      return '<span class="pill" style="background:#eedaf7;color:#591873">Refunded</span>';
    default:
      return '<span class="pill orange">Payment Due</span>';
  }
}

async function createOrder() {
  if (!currentCustomer) {
    setNotice('#orderNotice', 'Select or save a customer first.');
    return;
  }
  if (!currentMeasurement) {
    setNotice('#orderNotice', 'Select or save at least one measurement snapshot before creating the bill.');
    return;
  }
  if (!cart.length) {
    setNotice('#orderNotice', 'Add at least one tailoring service item to the bill.');
    return;
  }

  const advance = Number(val('advanceAmount') || 0);
  const discount = Number(val('discount') || 0);

  try {
    const result = await API.post('/api/tailor/orders', {
      customerId: currentCustomer.id,
      measurementId: currentMeasurement,
      dueDate: val('dueDate') || null,
      notes: val('orderNotes'),
      discount: discount,
      advanceAmount: advance,
      advanceMethod: val('advanceMethod'),
      advanceRef: val('advanceRef'),
      items: cart
    });

    renderInvoiceAndSharing(result);
    setNotice('#orderNotice', `Bill ${result.order_number} saved successfully in PostgreSQL!`, true);
    cart = [];
    drawCart();
    document.getElementById('advanceAmount').value = '0';
    document.getElementById('advanceRef').value = '';
    await loadOrders();
    if (currentCustomer) await loadCustomerOrdersHistory(currentCustomer.id);
  } catch (err) {
    setNotice('#orderNotice', err.message);
  }
}

function renderInvoiceAndSharing(result) {
  const customerPhone = whatsappDigits(currentCustomer ? currentCustomer.phone : result.phone);
  const due = Number(result.total) - Number(result.amount_paid);

  const message = [
    `*${shopSettings.shop_name || 'Jolly Ladies Tailor'}*`,
    `Invoice: *${result.order_number}*`,
    `Customer: ${currentCustomer ? currentCustomer.name : result.customer}`,
    `Date: ${new Date().toLocaleDateString()}`,
    result.due_date ? `Promised Delivery: ${result.due_date}` : '',
    `--------------------------`,
    ...(result.items || cart).map(i => `• ${i.item_name || i.name} x ${i.quantity}: ${money(i.amount || (i.rate * i.quantity))}`),
    `--------------------------`,
    `Total: ${money(result.total)}`,
    Number(result.amount_paid) > 0 ? `Paid / Advance: ${money(result.amount_paid)}` : '',
    `Outstanding Due: ${money(due)}`,
    `Status: ${result.status}`,
    shopSettings.upi_id ? `\nUPI Payment ID: ${shopSettings.upi_id}` : '',
    `\nThank you for choosing ${shopSettings.shop_name || 'Jolly Ladies Tailor'}!`
  ].filter(Boolean).join('\n');

  const row = document.getElementById('orderResult');
  row.classList.remove('hidden');
  row.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px">
      <div>
        <h3 style="margin:0;color:var(--violet)">Bill Created: ${escapeHTML(result.order_number)}</h3>
        <p class="muted" style="margin:4px 0">Customer: <strong>${escapeHTML(currentCustomer ? currentCustomer.name : result.customer)}</strong> · Due: <strong>${money(due)}</strong></p>
      </div>
      <div class="form-actions" style="margin:0">
        <button class="btn orange" id="shareBill" type="button">📲 Share e-Bill via WhatsApp</button>
        <button class="btn ghost" id="copyBillText" type="button">📋 Copy Text</button>
        <button class="btn" id="printBill" type="button">🖨️ Print Invoice / PDF</button>
      </div>
    </div>

    <!-- Printable Invoice Structure -->
    <div class="preview" id="printableBill" style="margin-top:16px;background:#fff;padding:24px;border-radius:12px;border:1px solid #ccc;color:#000">
      <div style="text-align:center;border-bottom:2px solid var(--violet);padding-bottom:12px">
        <h2 style="margin:0;font-family:Georgia,serif;color:var(--violet);font-size:26px">${escapeHTML(shopSettings.shop_name || 'Jolly Ladies Tailor')}</h2>
        <p style="margin:4px 0;font-size:13px">${escapeHTML(shopSettings.shop_address || 'Kolkata, West Bengal')}</p>
        <p style="margin:4px 0;font-size:13px">Phone: ${escapeHTML(shopSettings.shop_phone || '')} · WhatsApp: ${escapeHTML(shopSettings.whatsapp_number || '')}</p>
      </div>

      <div style="display:flex;justify-content:space-between;margin:16px 0;font-size:13px;flex-wrap:wrap;gap:12px">
        <div>
          <strong>Billed To:</strong><br>
          ${escapeHTML(currentCustomer ? currentCustomer.name : result.customer)}<br>
          Phone: ${escapeHTML(currentCustomer ? currentCustomer.phone : result.phone)}<br>
          ${(currentCustomer && currentCustomer.address) ? escapeHTML(currentCustomer.address) : ''}
        </div>
        <div style="text-align:right">
          <strong>Bill No:</strong> ${escapeHTML(result.order_number)}<br>
          <strong>Date:</strong> ${new Date().toLocaleDateString()}<br>
          ${result.due_date ? `<strong>Delivery Date:</strong> ${result.due_date}<br>` : ''}
          <strong>Order Status:</strong> ${escapeHTML(result.status)}<br>
          <strong>Payment:</strong> ${escapeHTML(result.payment_status || 'PAYMENT_DUE')}
        </div>
      </div>

      <table style="width:100%;border-collapse:collapse;margin:16px 0;font-size:13px">
        <thead>
          <tr style="background:#f3e8f4;border-bottom:1px solid #999">
            <th style="padding:8px;text-align:left">Service Item</th>
            <th style="padding:8px;text-align:center">Qty</th>
            <th style="padding:8px;text-align:right">Rate</th>
            <th style="padding:8px;text-align:right">Amount</th>
          </tr>
        </thead>
        <tbody>
          ${(result.items || cart).map(x => `
            <tr style="border-bottom:1px solid #eee">
              <td style="padding:8px">${escapeHTML(x.item_name || x.name)}</td>
              <td style="padding:8px;text-align:center">${x.quantity}</td>
              <td style="padding:8px;text-align:right">${money(x.unit_rate || x.rate)}</td>
              <td style="padding:8px;text-align:right">${money(x.amount || (x.rate * x.quantity))}</td>
            </tr>
          `).join('')}
        </tbody>
      </table>

      <div style="display:flex;justify-content:flex-end;margin-top:12px">
        <div style="min-width:240px;font-size:14px;line-height:1.6">
          <div style="display:flex;justify-content:space-between"><span>Subtotal:</span><span>${money(result.subtotal || result.total)}</span></div>
          ${Number(result.discount) > 0 ? `<div style="display:flex;justify-content:space-between"><span>Discount:</span><span>- ${money(result.discount)}</span></div>` : ''}
          <div style="display:flex;justify-content:space-between;border-top:1px solid #444;font-weight:700;padding-top:4px">
            <span>Net Total:</span><span>${money(result.total)}</span>
          </div>
          <div style="display:flex;justify-content:space-between"><span>Amount Paid:</span><span>${money(result.amount_paid)}</span></div>
          <div style="display:flex;justify-content:space-between;font-weight:700;color:#c00;border-top:1px dashed #999;padding-top:4px">
            <span>Balance Due:</span><span>${money(due)}</span>
          </div>
        </div>
      </div>

      ${shopSettings.upi_id || shopSettings.gpayQrUrl ? `
        <div style="margin-top:20px;border-top:1px solid #ddd;padding-top:12px;text-align:center">
          <p style="margin:4px 0;font-size:13px;font-weight:700">Scan to pay via Google Pay / UPI</p>
          ${shopSettings.gpayQrUrl ? `<img src="${escapeHTML(shopSettings.gpayQrUrl)}" alt="UPI QR code" style="width:130px;height:auto;margin:6px auto;display:block">` : ''}
          ${shopSettings.upi_id ? `<p style="margin:2px 0;font-size:12px;color:#555">UPI ID: <strong>${escapeHTML(shopSettings.upi_id)}</strong></p>` : ''}
        </div>
      ` : ''}

      <div style="margin-top:20px;text-align:center;font-size:12px;color:#777;border-top:1px solid #eee;padding-top:8px">
        Thank you for choosing ${escapeHTML(shopSettings.shop_name || 'Jolly Ladies Tailor')}! Stitched with care to fit you.
      </div>
    </div>
  `;

  document.getElementById('shareBill').onclick = () => {
    if (!customerPhone) {
      alert('Customer phone number is missing or invalid.');
      return;
    }
    window.open(`https://wa.me/${customerPhone}?text=${encodeURIComponent(message)}`, '_blank', 'noopener,noreferrer');
  };

  document.getElementById('copyBillText').onclick = () => {
    navigator.clipboard.writeText(message).then(() => {
      alert('E-bill text copied to clipboard!');
    }).catch(() => {
      prompt('Copy the bill text below:', message);
    });
  };

  document.getElementById('printBill').onclick = () => {
    const printable = document.getElementById('printableBill');
    const w = window.open('', '_blank');
    w.document.write(`
      <!doctype html>
      <html>
        <head>
          <title>Invoice - ${escapeHTML(result.order_number)}</title>
          <style>
            body { font-family: Inter, Arial, sans-serif; margin: 20px; color: #000; }
            table { width: 100%; border-collapse: collapse; }
            th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
            th { background: #f2e4f2; }
            @media print { body { margin: 0; } }
          </style>
        </head>
        <body>
          ${printable.outerHTML}
        </body>
      </html>
    `);
    w.document.close();
    w.focus();
    setTimeout(() => { w.print(); }, 250);
  };
}

async function loadOrders() {
  try {
    orders = await API.get('/api/tailor/orders');
    const body = document.getElementById('ordersRows');

    body.innerHTML = orders.length ? orders.map(o => `
      <tr>
        <td><strong>${escapeHTML(o.order_number)}</strong></td>
        <td>${escapeHTML(o.customer)}<br><small class="muted">${escapeHTML(o.phone)}</small></td>
        <td>
          <select data-status="${o.id}">
            ${ORDER_STATUSES.map(s => `
              <option value="${s.value}" ${s.value === o.status ? 'selected' : ''}>${s.label}</option>
            `).join('')}
          </select>
        </td>
        <td>${formatPaymentStatusBadge(o.payment_status)}</td>
        <td>${money(o.total)}</td>
        <td>${money(o.amount_paid)}</td>
        <td><strong>${money(o.due)}</strong></td>
        <td>${o.due_date ? escapeHTML(o.due_date) : '—'}</td>
        <td>
          <button class="btn ghost" type="button" data-wa-order="${o.id}">WhatsApp</button>
          <button class="btn ghost" type="button" data-view-order="${o.id}">View</button>
        </td>
      </tr>
    `).join('') : '<tr><td colspan="9">No orders recorded yet.</td></tr>';

    body.querySelectorAll('[data-status]').forEach(sel => {
      sel.onchange = async () => {
        try {
          await API.patch('/api/tailor/orders/' + sel.dataset.status + '/status', { status: sel.value });
        } catch (e) {
          alert(e.message);
          await loadOrders();
        }
      };
    });

    body.querySelectorAll('[data-wa-order]').forEach(btn => {
      btn.onclick = async () => {
        try {
          const o = await API.get('/api/tailor/orders/' + btn.dataset.waOrder);
          const phone = whatsappDigits(o.phone);
          const msg = [
            `*${shopSettings.shop_name || 'Jolly Ladies Tailor'}*`,
            `E-bill: *${o.order_number}*`,
            `Customer: ${o.customer}`,
            `Status: ${o.status}`,
            `Total: ${money(o.total)}`,
            `Paid: ${money(o.amount_paid)}`,
            `Due: ${money(o.due)}`,
            shopSettings.upi_id ? `UPI ID: ${shopSettings.upi_id}` : '',
            `Thank you!`
          ].filter(Boolean).join('\n');
          if (phone) {
            window.open(`https://wa.me/${phone}?text=${encodeURIComponent(msg)}`, '_blank', 'noopener,noreferrer');
          } else {
            alert('Customer phone number is missing.');
          }
        } catch (e) {
          alert(e.message);
        }
      };
    });

    body.querySelectorAll('[data-view-order]').forEach(btn => {
      btn.onclick = async () => {
        try {
          const ord = await API.get('/api/tailor/orders/' + btn.dataset.viewOrder);
          renderInvoiceAndSharing(ord);
          window.scrollTo({ top: document.getElementById('orderResult').offsetTop - 50, behavior: 'smooth' });
        } catch (e) {
          alert(e.message);
        }
      };
    });

    // Populate Payment Order Dropdown
    const open = orders.filter(o => Number(o.due) > 0);
    const sel = document.getElementById('paymentOrder');
    sel.innerHTML = open.map(o => `
      <option value="${o.id}">
        ${escapeHTML(o.order_number)} · ${escapeHTML(o.customer)} · Due: ${money(o.due)}
      </option>
    `).join('') || '<option value="">No outstanding orders</option>';

    if (open[0]) {
      document.getElementById('paymentAmount').value = Number(open[0].due).toFixed(2);
    }
  } catch (err) {
    console.error('Failed to load orders', err);
  }
}

async function recordPaymentOrRefund(e) {
  e.preventDefault();
  const id = val('paymentOrder');
  if (!id) {
    setNotice('#paymentNotice', 'Please select an order from the list.');
    return;
  }
  const type = val('paymentTypeSelect');
  const amount = Number(val('paymentAmount'));

  try {
    if (type === 'REFUND') {
      await API.post('/api/tailor/orders/' + id + '/refunds', {
        amount: amount,
        reason: val('paymentRef') || 'Customer requested refund'
      });
      setNotice('#paymentNotice', `Refund of ${money(amount)} recorded successfully.`, true);
    } else {
      await API.post('/api/tailor/orders/' + id + '/payments', {
        amount: amount,
        method: val('paymentMethod'),
        reference: val('paymentRef')
      });
      setNotice('#paymentNotice', `Payment of ${money(amount)} recorded in database. Verify UPI arrival in GPay.`, true);
    }
    await loadOrders();
    if (currentCustomer) await loadCustomerOrdersHistory(currentCustomer.id);
  } catch (err) {
    setNotice('#paymentNotice', err.message);
  }
}

async function loadInitial() {
  try {
    shopSettings = await API.get('/api/public/shop').catch(() => ({}));
    await Promise.all([
      loadCustomers(),
      loadProducts(),
      loadOrders()
    ]);
    drawMeasurementFields();
    drawCart();
  } catch (e) {
    document.getElementById('workspace').classList.add('hidden');
    document.getElementById('loginPanel').classList.remove('hidden');
    setNotice('#loginNotice', e.message);
  }
}

function logout() {
  API.post('/api/auth/logout', {}).finally(() => location.reload());
}

// Event Bindings
document.getElementById('loginForm').addEventListener('submit', doLogin);
document.getElementById('customerForm').addEventListener('submit', saveCustomer);
document.getElementById('newCustomer').addEventListener('click', () => {
  currentCustomer = null;
  currentMeasurement = null;
  document.getElementById('customerSelect').value = '';
  drawMeasurementFields();
  document.getElementById('measureNotes').value = '';
  document.getElementById('fit').value = 'Regular';
  document.getElementById('cName').value = '';
  document.getElementById('cPhone').value = '';
  document.getElementById('cAddress').value = '';
  document.getElementById('cNotes').value = '';
  document.getElementById('measurementFieldset').disabled = true;
  document.getElementById('measurementLock').hidden = false;
  document.getElementById('currentCustomer').hidden = true;
  document.getElementById('customerOrdersBox').classList.add('hidden');
  document.getElementById('measureHistory').innerHTML = '<tr><td colspan="4">Save a customer first.</td></tr>';
});
document.getElementById('measurementForm').addEventListener('submit', saveMeasurement);
document.getElementById('garment').addEventListener('change', drawMeasurementFields);
document.getElementById('unit').addEventListener('change', () => {
  const u = val('unit') === 'in' ? 'Inches' : 'cm';
  document.querySelectorAll('[data-measure]').forEach(input => input.placeholder = u);
});
document.getElementById('customerSelect').addEventListener('change', e => {
  chooseCustomer(e.target.value).catch(err => setNotice('#customerNotice', err.message));
});
document.getElementById('customerSearch').addEventListener('change', () => {
  loadCustomers(val('customerSearch')).catch(e => setNotice('#customerNotice', e.message));
});
document.getElementById('orderProduct').addEventListener('change', updateRate);
document.getElementById('addOrderItem').addEventListener('click', addCartItem);
document.getElementById('createOrder').addEventListener('click', createOrder);
document.getElementById('discount').addEventListener('input', drawCart);
document.getElementById('paymentForm').addEventListener('submit', recordPaymentOrRefund);
document.getElementById('refreshOrders').addEventListener('click', loadOrders);
document.getElementById('logout').addEventListener('click', logout);

// Auto-session check on page load
(async () => {
  try {
    const me = await API.get('/api/auth/me');
    document.getElementById('loginPanel').classList.add('hidden');
    document.getElementById('workspace').classList.remove('hidden');
    document.getElementById('logout').classList.remove('hidden');
    document.getElementById('whoami').textContent = `${me.username} · ${me.role}`;
    await loadInitial();
  } catch (e) {
    /* Anonymous visitor */
  }
})();
