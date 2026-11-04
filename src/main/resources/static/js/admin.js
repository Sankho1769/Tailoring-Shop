let statData = {};
let categories = [];
let products = [];
let users = [];
let requests = [];
let adminCustomers = [];
let adminPayments = [];
let currentEditingProductId = null;
let currentViewedMeasurement = null;
let adminCurrentCustomerId = null;
let adminCustomerMeasurements = [];

const ADMIN_MEASUREMENT_DEFINITIONS = {
  'CHURIDAR / BLOUSE': [
    'length', 'chest', 'upperChest', 'pet', 'put', 'hand', 'cup', 'neck',
    'sesto', 'fittings', 'bodyLength', 'underarm', 'backLength', 'frontLength', 'width', 'falsePar'
  ],
  'PANT': [
    'length', 'waist', 'hip', 'high', 'thai', 'bottom', 'underarm'
  ],
  'CHURIDAR / BLOUSE & PANT': {
    'CHURIDAR / BLOUSE': [
      'length', 'chest', 'upperChest', 'pet', 'put', 'hand', 'cup', 'neck',
      'sesto', 'fittings', 'bodyLength', 'underarm', 'backLength', 'frontLength', 'width', 'falsePar'
    ],
    'PANT': [
      'length', 'waist', 'hip', 'high', 'thai', 'bottom', 'underarm'
    ]
  },
  'Blouse': ['shoulder','bust','upperBust','blouseWaist','blouseLength','sleeveLength','armhole','biceps','wrist','frontNeck','backNeck','apexDistance'],
  'Kurti': ['shoulder','bust','waist','hip','armhole','sleeveLength','kurtiLength','neckDepth'],
  'Salwar / Kameez': ['shoulder','bust','waist','hip','kameezLength','sleeveLength','armhole','neckDepth','salwarLength','thigh','ankle'],
  'Pant': ['waist','hip','rise','thigh','knee','bottom','inseam','outseam','pantLength'],
  'Lehenga': ['waist','hip','lehengaLength','flare','waistBand'],
  'Alteration': ['shoulder','bust','waist','hip','sleeveLength','lengthToAdjust','waistToAdjust','notesMeasurement'],
  'General body measurements': ['shoulder','bust','upperBust','waist','hip','armhole','biceps','wrist','fullLength','inseam']
};

const ADMIN_SLIP_LABELS = {
  length: 'Length',
  chest: 'Chest',
  upperChest: 'Upper Chest',
  pet: 'Pet',
  put: 'Put',
  hand: 'Hand',
  cup: 'Cup',
  neck: 'Neck',
  sesto: 'Sesto',
  fittings: 'Fittings',
  bodyLength: 'Body Length',
  underarm: 'Underarm',
  backLength: 'Back Length',
  frontLength: 'Front Length',
  width: 'Width',
  falsePar: 'False Par',
  waist: 'Waist',
  hip: 'Hip',
  high: 'High',
  thai: 'Thai',
  bottom: 'Bottom',
  pant_length: 'Length (Pant)',
  pant_waist: 'Waist',
  pant_hip: 'Hip',
  pant_high: 'High',
  pant_thai: 'Thai',
  pant_bottom: 'Bottom',
  pant_underarm: 'Underarm (Pant)',
  shoulder: 'Shoulder',
  bust: 'Bust / Chest',
  upperBust: 'Upper bust',
  blouseWaist: 'Blouse waist',
  blouseLength: 'Blouse length',
  sleeveLength: 'Sleeve length',
  armhole: 'Armhole',
  biceps: 'Biceps / Upper arm',
  wrist: 'Wrist',
  frontNeck: 'Front neck depth',
  backNeck: 'Back neck depth',
  apexDistance: 'Apex distance',
  kameezLength: 'Kameez length',
  neckDepth: 'Neck depth',
  salwarLength: 'Salwar length',
  thigh: 'Thigh',
  ankle: 'Ankle opening',
  rise: 'Rise / crotch',
  knee: 'Knee',
  inseam: 'Inseam',
  outseam: 'Outseam',
  pantLength: 'Pant length',
  frockLength: 'Frock length',
  lehengaLength: 'Lehenga length',
  flare: 'Lehenga flare',
  waistBand: 'Waistband',
  kurtiLength: 'Kurti length',
  fullLength: 'Full body length',
  lengthToAdjust: 'Length to adjust',
  waistToAdjust: 'Waist adjustment',
  notesMeasurement: 'Other measurement'
};

const av = id => {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
};
const val = av;

async function login(e) {
  e.preventDefault();
  const username = av('username');
  const password = document.getElementById('password') ? document.getElementById('password').value : '';
  const rememberMe = document.getElementById('rememberMe') ? document.getElementById('rememberMe').checked : true;
  try {
    await API.csrf();
    const me = await API.post('/api/auth/login', { username, password, rememberMe });
    await API.csrf();
    if (me.role !== 'ADMIN') {
      throw new Error('Admin role is required to access the Admin Console.');
    }
    if (document.getElementById('password')) document.getElementById('password').value = '';
    await showAdmin(me);
  } catch (err) {
    setNotice('#loginNotice', err.message);
  }
}

async function showAdmin(me) {
  document.getElementById('loginPanel').classList.add('hidden');
  document.getElementById('adminApp').classList.remove('hidden');
  document.getElementById('logout').classList.remove('hidden');

  const custModal = document.getElementById('adminCustomerModal');
  if (custModal) custModal.classList.add('hidden');

  const initialLoads = [
    loadStats(),
    loadCategories(),
    loadProducts(),
    loadPriceAdjustments(),
    loadAdminCustomers(),
    loadAdminPayments(),
    loadUsers(),
    loadShopSettings()
  ];
  if (document.getElementById('requestsRows')) {
    initialLoads.push(loadRequests());
  }
  await Promise.all(initialLoads);

  if (window.location.hash) {
    setTimeout(() => {
      const el = document.querySelector(window.location.hash);
      if (el) el.scrollIntoView();
    }, 200);
  }
}

async function loadStats() {
  try {
    const range = av('range') || 'month';
    statData = await API.get('/api/admin/stats?range=' + encodeURIComponent(range));

    document.getElementById('statBilled').textContent = money(statData.billed);
    document.getElementById('statCollected').textContent = money(statData.collected);
    document.getElementById('statDue').textContent = money(statData.due);
    document.getElementById('statOrders').textContent = Number(statData.orders || 0).toLocaleString('en-IN');
    document.getElementById('statCustomers').textContent = Number(statData.customers || 0).toLocaleString('en-IN');
    document.getElementById('statProducts').textContent = Number(statData.products || 0).toLocaleString('en-IN');
    const sr = document.getElementById('statRequests');
    if (sr) sr.textContent = Number(statData.requests || 0).toLocaleString('en-IN');
    const ordBadge = document.getElementById('ordersCountBadge');
    if (ordBadge) ordBadge.textContent = (statData.recentOrders || []).length;
    document.getElementById('statAverage').textContent = money(
      Number(statData.orders) ? Number(statData.billed) / Number(statData.orders) : 0
    );

    // Sales Bar Chart
    const days = statData.salesByDay || [];
    const max = Math.max(1, ...days.map(d => Number(d.sales || 0)));
    document.getElementById('salesChart').innerHTML = days.map(d => `
      <div style="display:grid;grid-template-columns:80px 1fr 110px;gap:12px;align-items:center;font-size:13px">
        <span>${escapeHTML(d.day)}</span>
        <div style="height:18px;border-radius:6px;background:#f3e6f3;overflow:hidden">
          <div style="height:100%;width:${Math.max(0, (Number(d.sales || 0) / max) * 100)}%;background:var(--orange);border-radius:6px;transition:width 0.3s"></div>
        </div>
        <strong style="text-align:right">${money(d.sales)}</strong>
      </div>
    `).join('') || '<p class="muted">No sales recorded in this period.</p>';

    // Top Services
    document.getElementById('topServicesRows').innerHTML = (statData.topServices || []).map(s => `
      <tr>
        <td><strong>${escapeHTML(s.name)}</strong></td>
        <td>${Number(s.quantity || 0)}</td>
        <td><strong>${money(s.revenue)}</strong></td>
      </tr>
    `).join('') || '<tr><td colspan="3">No service billing data in this period.</td></tr>';

    // Payment Methods
    document.getElementById('paymentMethodsRows').innerHTML = (statData.paymentMethods || []).map(p => `
      <tr>
        <td><strong>${escapeHTML(p.method)}</strong></td>
        <td>${Number(p.transactions || 0)}</td>
        <td><strong>${money(p.amount)}</strong></td>
      </tr>
    `).join('') || '<tr><td colspan="3">No payments recorded in this period.</td></tr>';

    // Recent Shop Orders Table
    const recentTbody = document.getElementById('adminRecentOrdersRows');
    if (recentTbody) {
      recentTbody.innerHTML = (statData.recentOrders || []).map(o => `
        <tr>
          <td><strong>${escapeHTML(o.order_number)}</strong></td>
          <td>
            <button type="button" class="btn-link" style="background:none;border:none;padding:0;color:var(--violet);font-weight:700;text-decoration:underline;cursor:pointer;font-family:inherit;font-size:inherit;text-align:left" onclick="focusCustomerInDirectory('${escapeHTML(o.customer)}', '${escapeHTML(o.phone)}')">
              ${escapeHTML(o.customer)}
            </button>
            <br><small class="muted">${escapeHTML(o.phone)}</small>
          </td>
          <td>
            <select data-admin-order-status="${o.id}" style="font-size:11px;padding:3px 6px;border-radius:6px;font-weight:600;border:1px solid var(--pink)">
              ${['NEW','IN_PROGRESS','TRIAL','READY','DELIVERED','CANCELLED'].map(st => `
                <option value="${st}" ${st === o.status ? 'selected' : ''}>${st.replace('_', ' ')}</option>
              `).join('')}
            </select>
          </td>
          <td><span class="pill ${o.payment_status === 'PAID' ? '' : 'orange'}">${escapeHTML(o.payment_status)}</span></td>
          <td>${money(o.total)}</td>
          <td>${money(o.amount_paid)}</td>
          <td><strong style="color:${Number(o.due) > 0 ? '#b30000' : 'inherit'}">${money(o.due)}</strong></td>
          <td style="white-space:nowrap">
            <a class="btn light btn-sm" href="/bill.html?id=${o.id}" target="_blank" rel="noopener">View e-Bill ↗</a>
            <a class="btn orange btn-sm" href="/api/admin/orders/${o.id}/pdf" download="invoice-${escapeHTML(o.order_number)}.pdf">📄 PDF</a>
            <button class="btn ghost btn-sm" type="button" data-view-measure="${o.id}">📐 Measurements</button>
            ${Number(o.due) > 0 ? `<button class="btn orange btn-sm" type="button" data-pay-order="${o.id}" data-due="${o.due}" data-total="${o.total}" data-paid="${o.amount_paid}" data-ord-num="${escapeHTML(o.order_number)}" data-cust="${escapeHTML(o.customer)}">💳 Pay</button>` : ''}
          </td>
        </tr>
      `).join('') || '<tr><td colspan="8">No recent orders recorded.</td></tr>';

      recentTbody.querySelectorAll('[data-view-measure]').forEach(btn => {
        btn.onclick = () => openAdminMeasurementModal(btn.dataset.viewMeasure);
      });
      recentTbody.querySelectorAll('[data-pay-order]').forEach(btn => {
        btn.onclick = () => openAdminOrderPaymentModal(btn.dataset.payOrder, btn.dataset.total, btn.dataset.paid, btn.dataset.due, btn.dataset.ordNum, btn.dataset.cust);
      });
      recentTbody.querySelectorAll('[data-admin-order-status]').forEach(sel => {
        sel.onchange = () => handleAdminUpdateOrderStatus(sel.dataset.adminOrderStatus, sel.value);
      });
    }

    document.getElementById('statsNotice').hidden = true;
  } catch (e) {
    setNotice('#statsNotice', e.message);
  }
}

window.setCustomerOrderView = function(view) {
  const pCust = document.getElementById('subpanelCustomers');
  const pOrders = document.getElementById('subpanelRecentOrders');
  const tAll = document.getElementById('tabCustAll');
  const tCust = document.getElementById('tabCustOnly');
  const tOrders = document.getElementById('tabOrdersOnly');

  [tAll, tCust, tOrders].forEach(b => {
    if (b) {
      b.classList.remove('orange');
      b.classList.add('ghost');
    }
  });

  if (view === 'customers') {
    if (pCust) pCust.style.display = 'block';
    if (pOrders) pOrders.style.display = 'none';
    if (tCust) {
      tCust.classList.remove('ghost');
      tCust.classList.add('orange');
    }
  } else if (view === 'orders') {
    if (pCust) pCust.style.display = 'none';
    if (pOrders) pOrders.style.display = 'block';
    if (tOrders) {
      tOrders.classList.remove('ghost');
      tOrders.classList.add('orange');
    }
  } else {
    // 'all'
    if (pCust) pCust.style.display = 'block';
    if (pOrders) pOrders.style.display = 'block';
    if (tAll) {
      tAll.classList.remove('ghost');
      tAll.classList.add('orange');
    }
  }
};

window.focusCustomerInDirectory = async function(custName, custPhone) {
  setCustomerOrderView('all');
  const searchInput = document.getElementById('adminCustomerSearch');
  if (searchInput) {
    searchInput.value = custName || custPhone || '';
  }
  await loadAdminCustomers(custName || custPhone || '');

  let match = (adminCustomers || []).find(c =>
    (custPhone && c.phone === custPhone) ||
    (custName && c.name && c.name.toLowerCase() === custName.toLowerCase())
  );
  if (!match && adminCustomers && adminCustomers.length > 0) {
    match = adminCustomers[0];
  }
  if (match) {
    await openCustomerOrders(match.id, match.name);
    const detailBox = document.getElementById('adminCustomerOrdersDetail');
    if (detailBox) {
      detailBox.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }
};

async function loadAdminCustomers(q = '') {
  try {
    adminCustomers = await API.get('/api/admin/customers' + (q ? '?q=' + encodeURIComponent(q) : ''));
    window.adminCustomers = adminCustomers;
    const custBadge = document.getElementById('custCountBadge');
    if (custBadge) custBadge.textContent = adminCustomers.length;
    const tbody = document.getElementById('adminCustomersRows');

    tbody.innerHTML = adminCustomers.length ? adminCustomers.map(c => `
      <tr>
        <td><strong>${escapeHTML(c.name)}</strong><br><small class="muted">${escapeHTML(c.address || '')}</small></td>
        <td><a href="https://wa.me/${whatsappDigits(c.phone)}" target="_blank" rel="noopener">${escapeHTML(c.phone)}</a></td>
        <td>${c.total_orders}</td>
        <td>${money(c.total_spent)}</td>
        <td><strong style="color:${Number(c.total_due) > 0 ? 'var(--orange)' : 'inherit'}">${money(c.total_due)}</strong></td>
        <td>${c.last_order_date ? new Date(c.last_order_date).toLocaleDateString() : '—'}</td>
        <td style="white-space:nowrap">
          <button class="btn ghost btn-sm" type="button" data-cust-orders="${c.id}" data-cust-name="${escapeHTML(c.name)}">
            Orders (${c.total_orders})
          </button>
          <button class="btn ghost btn-sm" type="button" data-cust-measure="${c.id}" data-cust-name="${escapeHTML(c.name)}">
            📐 Measurements
          </button>
          <button class="btn light btn-sm" type="button" data-edit-cust="${c.id}">
            ✏️ Edit
          </button>
          <a class="btn ghost btn-sm" href="/tailor.html?selectCustomer=${c.id}" title="Create new order in Tailor Workspace">
            ＋ New Order ↗
          </a>
        </td>
      </tr>
    `).join('') : '<tr><td colspan="7">No customers match the query.</td></tr>';

    tbody.querySelectorAll('[data-cust-orders]').forEach(btn => {
      btn.onclick = () => openCustomerOrders(btn.dataset.custOrders, btn.dataset.custName);
    });
    tbody.querySelectorAll('[data-cust-measure]').forEach(btn => {
      btn.onclick = () => openAdminCustomerMeasurementsModal(btn.dataset.custMeasure, btn.dataset.custName);
    });
    tbody.querySelectorAll('[data-edit-cust]').forEach(btn => {
      btn.onclick = () => {
        const cust = (adminCustomers || []).find(x => String(x.id) === String(btn.dataset.editCust));
        if (cust) openAdminEditCustomerModal(cust);
      };
    });
  } catch (e) {
    console.error('Error loading admin customers', e);
  }
}

async function openCustomerOrders(customerId, customerName) {
  try {
    const list = await API.get('/api/admin/customers/' + customerId + '/orders');
    const container = document.getElementById('adminCustomerOrdersDetail');
    document.getElementById('adminCustomerDetailTitle').textContent = `Orders for ${customerName} (${list.length} total)`;
    const tbody = document.getElementById('adminCustomerOrdersRows');

    tbody.innerHTML = list.length ? list.map(o => `
      <tr>
        <td><strong>${escapeHTML(o.order_number)}</strong></td>
        <td>
          <select data-admin-order-status="${o.id}" style="font-size:11px;padding:3px 6px;border-radius:6px;font-weight:600;border:1px solid var(--pink)">
            ${['NEW','IN_PROGRESS','TRIAL','READY','DELIVERED','CANCELLED'].map(st => `
              <option value="${st}" ${st === o.status ? 'selected' : ''}>${st.replace('_', ' ')}</option>
            `).join('')}
          </select>
        </td>
        <td><span class="pill ${o.payment_status === 'PAID' ? '' : 'orange'}">${escapeHTML(o.payment_status)}</span></td>
        <td>${money(o.total)}</td>
        <td>${money(o.amount_paid)}</td>
        <td><strong style="color:${Number(o.due) > 0 ? '#b30000' : 'inherit'}">${money(o.due)}</strong></td>
        <td>${o.due_date ? escapeHTML(o.due_date) : '—'}</td>
        <td>${new Date(o.created_at).toLocaleDateString()}</td>
        <td style="white-space:nowrap">
          <a class="btn light btn-sm" href="/bill.html?id=${o.id}" target="_blank" rel="noopener">View e-Bill ↗</a>
          <a class="btn orange btn-sm" href="/api/admin/orders/${o.id}/pdf" download="invoice-${escapeHTML(o.order_number)}.pdf">📄 PDF</a>
          <button class="btn ghost btn-sm" type="button" data-view-measure="${o.id}">📐 Measurements</button>
          ${Number(o.due) > 0 ? `<button class="btn orange btn-sm" type="button" data-pay-order="${o.id}" data-due="${o.due}" data-total="${o.total}" data-paid="${o.amount_paid}" data-ord-num="${escapeHTML(o.order_number)}" data-cust="${escapeHTML(customerName)}">💳 Pay</button>` : ''}
        </td>
      </tr>
    `).join('') : '<tr><td colspan="9">No orders for this customer.</td></tr>';

    tbody.querySelectorAll('[data-view-measure]').forEach(btn => {
      btn.onclick = () => openAdminMeasurementModal(btn.dataset.viewMeasure);
    });
    tbody.querySelectorAll('[data-pay-order]').forEach(btn => {
      btn.onclick = () => openAdminOrderPaymentModal(btn.dataset.payOrder, btn.dataset.total, btn.dataset.paid, btn.dataset.due, btn.dataset.ordNum, btn.dataset.cust);
    });
    tbody.querySelectorAll('[data-admin-order-status]').forEach(sel => {
      sel.onchange = () => handleAdminUpdateOrderStatus(sel.dataset.adminOrderStatus, sel.value);
    });

    container.classList.remove('hidden');
    container.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  } catch (e) {
    alert('Failed to load customer orders: ' + e.message);
  }
}

async function loadAdminPayments(q = '') {
  try {
    adminPayments = await API.get('/api/admin/payments' + (q ? '?q=' + encodeURIComponent(q) : ''));
    const tbody = document.getElementById('adminPaymentsRows');

    tbody.innerHTML = adminPayments.length ? adminPayments.map(p => `
      <tr>
        <td>${new Date(p.received_at).toLocaleDateString()}</td>
        <td><strong>${escapeHTML(p.order_number)}</strong></td>
        <td>${escapeHTML(p.customer)}<br><small class="muted">${escapeHTML(p.phone)}</small></td>
        <td><strong>${money(p.amount)}</strong></td>
        <td><span class="pill ${p.payment_type === 'REFUND' ? 'orange' : ''}">${escapeHTML(p.payment_type || 'PAYMENT')}</span></td>
        <td>${escapeHTML(p.method)}</td>
        <td>${escapeHTML(p.reference || '')} ${p.note ? `<br><small class="muted">${escapeHTML(p.note)}</small>` : ''}</td>
        <td>${escapeHTML(p.collector || 'Admin')}</td>
      </tr>
    `).join('') : '<tr><td colspan="8">No payment records found.</td></tr>';
  } catch (e) {
    console.error('Error loading admin payments', e);
  }
}

async function loadCategories() {
  categories = await API.get('/api/admin/categories');
  const sel = document.getElementById('pCategory');
  sel.innerHTML = '<option value="">Select Category</option>' + categories.filter(c => c.active).map(c =>
    `<option value="${c.id}">${escapeHTML(c.name)}</option>`
  ).join('');

  document.getElementById('categoriesList').innerHTML = categories.map(c => `
    <div class="card" style="padding:12px;display:flex;align-items:center;justify-content:space-between;gap:8px">
      <div>
        <strong>${escapeHTML(c.name)}</strong>
        <div class="muted">${escapeHTML(c.description || 'Garment stitching services')}</div>
      </div>
      ${c.active
        ? `<button class="btn ghost btn-sm" type="button" data-deactivate-category="${c.id}">Deactivate</button>`
        : '<span class="pill orange">Inactive</span>'}
    </div>
  `).join('') || '<p class="muted">No categories defined yet.</p>';

  document.querySelectorAll('[data-deactivate-category]').forEach(b => {
    b.onclick = async () => {
      if (!confirm('Deactivate this garment category?')) return;
      try {
        await API.delete('/api/admin/categories/' + b.dataset.deactivateCategory);
        await Promise.all([loadCategories(), loadProducts()]);
        setNotice('#categoryNotice', 'Category deactivated.', true);
      } catch (e) {
        alert(e.message);
        setNotice('#categoryNotice', e.message);
      }
    };
  });
}

function toggleStockField() {
  const pType = document.getElementById('pType').value;
  const stockInput = document.getElementById('pStock');
  const stockWrap = document.getElementById('pStockWrap');
  if (pType === 'READY_MADE') {
    stockInput.disabled = false;
    stockWrap.style.opacity = '1';
  } else {
    stockInput.disabled = true;
    stockInput.value = '0';
    stockWrap.style.opacity = '0.5';
  }
}

function filterProductsTable() {
  const q = (document.getElementById('productSearchInput')?.value || '').toLowerCase().trim();
  const rows = document.querySelectorAll('#productsRows tr');
  rows.forEach(tr => {
    if (tr.children.length < 2) return;
    const text = tr.textContent.toLowerCase();
    tr.style.display = (!q || text.includes(q)) ? '' : 'none';
  });
}

async function loadProducts() {
  products = await API.get('/api/admin/products');
  const badge = document.getElementById('productCountBadge');
  if (badge) badge.textContent = products.length;

  const tbody = document.getElementById('productsRows');
  tbody.innerHTML = products.map(p => `
    <tr>
      <td>
        <strong>${escapeHTML(p.name)}</strong>
        ${p.description ? `<br><small class="muted">${escapeHTML(p.description)}</small>` : ''}
      </td>
      <td><span class="pill" style="font-size:11px">${p.product_type === 'READY_MADE' ? 'Ready-Made' : 'Service'}</span></td>
      <td>${escapeHTML(p.category || '—')}</td>
      <td><strong>${money(p.default_rate)}</strong></td>
      <td>${p.product_type === 'READY_MADE' ? p.stock_quantity : '—'}</td>
      <td>${p.active ? '<span class="pill">Active</span>' : '<span class="pill orange">Inactive</span>'}</td>
      <td>
        ${p.imageUrl ? `<img src="${escapeHTML(p.imageUrl)}" alt="" style="width:36px;height:36px;object-fit:cover;border-radius:6px;vertical-align:middle;margin-right:6px">` : ''}
        <label class="btn ghost btn-sm" style="display:inline-flex;padding:4px 8px;font-size:11px;cursor:pointer">
          Photo
          <input type="file" accept="image/png,image/jpeg,image/webp,image/gif" data-image="${p.id}" hidden>
        </label>
      </td>
      <td style="white-space:nowrap">
        <button class="btn light btn-sm" type="button" data-edit-product="${p.id}">✏️ Edit</button>
        <button class="btn ghost btn-sm" type="button" data-toggle-product="${p.id}" data-active="${p.active}">
          ${p.active ? 'Deactivate' : 'Activate'}
        </button>
      </td>
    </tr>
  `).join('') || '<tr><td colspan="8">No products/services configured yet.</td></tr>';

  tbody.querySelectorAll('[data-edit-product]').forEach(b => {
    b.onclick = () => startEditProduct(b.dataset.editProduct);
  });

  tbody.querySelectorAll('[data-toggle-product]').forEach(b => {
    b.onclick = async () => {
      try {
        await API.patch('/api/admin/products/' + b.dataset.toggleProduct, {
          active: b.dataset.active !== 'true'
        });
        await Promise.all([loadProducts(), loadStats()]);
      } catch (e) {
        setNotice('#productNotice', e.message);
      }
    };
  });

  tbody.querySelectorAll('[data-image]').forEach(inp => {
    inp.onchange = async () => {
      if (!inp.files[0]) return;
      const fd = new FormData();
      fd.append('file', inp.files[0]);
      try {
        await API.post('/api/admin/media/products/' + inp.dataset.image, fd);
        setNotice('#productNotice', 'Product image uploaded to Supabase Storage.', true);
        await loadProducts();
      } catch (e) {
        setNotice('#productNotice', e.message);
      }
    };
  });
}

function startEditProduct(id) {
  const p = products.find(x => String(x.id) === String(id));
  if (!p) return;
  currentEditingProductId = id;
  document.getElementById('pEditingId').value = id;
  document.getElementById('pName').value = p.name || '';
  document.getElementById('pCategory').value = p.category_id || '';
  document.getElementById('pType').value = p.product_type || 'SERVICE';
  document.getElementById('pRate').value = p.default_rate != null ? p.default_rate : '0';
  document.getElementById('pStock').value = p.stock_quantity != null ? p.stock_quantity : '0';
  document.getElementById('pDescription').value = p.description || '';
  toggleStockField();

  document.getElementById('productFormTitle').textContent = `✏️ Edit Garment / Service: ${p.name}`;
  document.getElementById('saveProductBtn').textContent = 'Save Changes';
  document.getElementById('cancelProductEditBtn').classList.remove('hidden');
  document.getElementById('servicesPricingSection').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function cancelProductEdit() {
  currentEditingProductId = null;
  document.getElementById('pEditingId').value = '';
  document.getElementById('productForm').reset();
  document.getElementById('pRate').value = '0';
  document.getElementById('pStock').value = '0';
  toggleStockField();
  document.getElementById('productFormTitle').textContent = '＋ Add New Service / Garment';
  document.getElementById('saveProductBtn').textContent = 'Add Service / Garment';
  document.getElementById('cancelProductEditBtn').classList.add('hidden');
}

async function handleProductSubmit(e) {
  e.preventDefault();
  const name = av('pName');
  const catId = av('pCategory') || null;
  const pType = av('pType') || 'SERVICE';
  const defaultRate = Number(av('pRate') || 0);
  const stockQuantity = pType === 'READY_MADE' ? Number(av('pStock') || 0) : 0;
  const description = av('pDescription');

  try {
    if (currentEditingProductId) {
      await API.patch('/api/admin/products/' + currentEditingProductId, {
        name,
        categoryId: catId,
        productType: pType,
        defaultRate,
        stockQuantity,
        description
      });
      setNotice('#productNotice', `Item "${name}" updated successfully.`, true);
    } else {
      await API.post('/api/admin/products', {
        name,
        categoryId: catId,
        productType: pType,
        defaultRate,
        stockQuantity,
        description
      });
      setNotice('#productNotice', `Item "${name}" created successfully.`, true);
    }
    cancelProductEdit();
    await Promise.all([loadProducts(), loadStats()]);
  } catch (err) {
    setNotice('#productNotice', err.message);
  }
}

async function addCategory(e) {
  e.preventDefault();
  try {
    await API.post('/api/admin/categories', {
      name: av('categoryName'),
      description: av('categoryDescription')
    });
    setNotice('#categoryNotice', 'Category created successfully.', true);
    e.target.reset();
    await loadCategories();
  } catch (err) {
    setNotice('#categoryNotice', err.message);
  }
}

async function loadPriceAdjustments() {
  try {
    const list = await API.get('/api/admin/price-adjustments');
    const tbody = document.getElementById('adjustmentsRows');
    tbody.innerHTML = list.length ? list.map(a => {
      const diff = Number(a.difference || 0);
      return `
      <tr>
        <td>${a.adjusted_at ? new Date(a.adjusted_at).toLocaleDateString() : '—'}</td>
        <td><strong>${escapeHTML(a.order_number)}</strong></td>
        <td>${escapeHTML(a.customer_name)}<br><small class="muted">${escapeHTML(a.customer_phone)}</small></td>
        <td>${escapeHTML(a.item_name)}</td>
        <td>${money(a.default_rate)}</td>
        <td><strong>${money(a.unit_rate)}</strong></td>
        <td style="font-weight:700;color:${diff < 0 ? '#b30000' : 'var(--green)'}">
          ${diff > 0 ? '+' : ''}${money(diff)}
        </td>
        <td style="max-width:200px;white-space:normal">${escapeHTML(a.adjustment_reason || '—')}</td>
        <td>${escapeHTML(a.adjusted_by_name || 'Staff')}</td>
      </tr>
    `}).join('') : '<tr><td colspan="9">No price adjustments recorded. Tailors have used default prices.</td></tr>';
  } catch (e) {
    console.error('Failed to load price adjustments', e);
  }
}

async function loadRequests() {
  const tbody = document.getElementById('requestsRows');
  if (!tbody) return;
  try {
    requests = await API.get('/api/admin/requests');

    tbody.innerHTML = requests.length ? requests.map(r => `
      <tr>
        <td><strong>${escapeHTML(r.name)}</strong></td>
        <td><a href="https://wa.me/${whatsappDigits(r.phone)}" target="_blank" rel="noopener">${escapeHTML(r.phone)}</a></td>
        <td>${r.email ? `<a href="mailto:${escapeHTML(r.email)}">${escapeHTML(r.email)}</a>` : '—'}</td>
        <td>${escapeHTML(r.requested_service || '—')}</td>
        <td style="max-width:240px;white-space:normal">${escapeHTML(r.message || '—')}</td>
        <td>${new Date(r.created_at).toLocaleDateString()}</td>
        <td>
          <select data-request-status="${r.id}">
            ${['NEW', 'CONTACTED', 'CONFIRMED', 'RESOLVED', 'CLOSED'].map(s => `
              <option value="${s}" ${s === r.status ? 'selected' : ''}>${s}</option>
            `).join('')}
          </select>
        </td>
      </tr>
    `).join('') : '<tr><td colspan="7">No consumer website enquiries received yet.</td></tr>';

    document.querySelectorAll('[data-request-status]').forEach(s => {
      s.onchange = async () => {
        try {
          await API.patch('/api/admin/requests/' + s.dataset.requestStatus + '/status', { status: s.value });
          await loadStats();
        } catch (e) {
          alert(e.message);
        }
      };
    });
  } catch (err) {
    console.warn('loadRequests error', err);
  }
}

// ============================================================================
// STAFF MANAGEMENT SYSTEM
// ============================================================================
async function loadUsers() {
  try {
    users = await API.get('/api/admin/staff');
    const tbody = document.getElementById('staffDirectoryRows');
    if (!tbody) return;

    tbody.innerHTML = users.map(u => {
      const isLastAdmin = u.role === 'ADMIN' && u.active && users.filter(x => x.role === 'ADMIN' && x.active).length <= 1;

      return `
        <tr>
          <td><strong>${escapeHTML(u.full_name || u.username)}</strong></td>
          <td><code>${escapeHTML(u.username)}</code></td>
          <td><span class="pill staff-badge-${u.role}">${escapeHTML(u.role)}</span></td>
          <td>
            <span class="staff-active-${u.active}">${u.active ? '● Active' : '○ Disabled'}</span>
            ${isLastAdmin ? '<br><small class="pill" style="font-size:10px;background:#fdeeee;color:#b30000">Sole Admin</small>' : ''}
          </td>
          <td style="white-space:nowrap">
            <button class="btn ghost btn-sm" type="button" onclick="openStaffHisab(${u.id})" title="View and record work, hisab, and settlements for this staff member">📋 Work &amp; Hisab</button>
            <button class="btn light btn-sm" type="button" data-edit-staff="${u.id}">✏️ Edit Staff</button>
            <button class="btn ghost btn-sm" type="button" data-reset-pw-staff="${u.id}" data-staff-user="${escapeHTML(u.username)}">🔑 Reset Password</button>
            <button class="btn ghost btn-sm" type="button" data-toggle-staff-active="${u.id}" data-active="${u.active}" ${isLastAdmin ? 'title="Cannot deactivate sole active admin"' : ''}>
              ${u.active ? 'Deactivate' : 'Activate'}
            </button>
          </td>
        </tr>
      `;
    }).join('') || '<tr><td colspan="5">No staff accounts found.</td></tr>';

    populateHisabStaffSelect(users);

    tbody.querySelectorAll('[data-edit-staff]').forEach(b => {
      const u = users.find(x => String(x.id) === String(b.dataset.editStaff));
      if (u) b.onclick = () => openEditStaffModal(u);
    });

    tbody.querySelectorAll('[data-reset-pw-staff]').forEach(b => {
      b.onclick = () => openResetStaffPwModal(b.dataset.resetPwStaff, b.dataset.staffUser);
    });

    tbody.querySelectorAll('[data-toggle-staff-active]').forEach(b => {
      b.onclick = async () => {
        const id = b.dataset.toggleStaffActive;
        const curActive = b.dataset.active === 'true';
        if (!confirm(`Are you sure you want to ${curActive ? 'disable' : 'enable'} this staff account?`)) return;
        try {
          await API.patch('/api/admin/staff/' + id, { active: !curActive });
          await loadUsers();
        } catch (e) {
          alert('Staff update error: ' + e.message);
        }
      };
    });
  } catch (err) {
    console.error('Failed to load staff list:', err);
  }
}

function toggleAddStaffCard() {
  const card = document.getElementById('addStaffCard');
  if (card) {
    card.classList.toggle('hidden');
    if (!card.classList.contains('hidden')) {
      document.getElementById('uFullName')?.focus();
    }
  }
}

async function addUser(e) {
  e.preventDefault();
  const fullName = av('uFullName');
  const username = av('uName');
  const role = av('uRole');
  const password = document.getElementById('uPassword')?.value || '';

  if (password.length < 12) {
    setNotice('#userNotice', 'Password must be at least 12 characters.');
    return;
  }

  try {
    await API.post('/api/admin/staff', {
      fullName,
      username,
      role,
      password,
      active: true
    });
    setNotice('#userNotice', `Staff account "${username}" created successfully.`, true);
    e.target.reset();
    toggleAddStaffCard();
    await loadUsers();
  } catch (err) {
    setNotice('#userNotice', err.message);
  }
}

function openEditStaffModal(u) {
  document.getElementById('editStaffId').value = u.id;
  document.getElementById('editStaffFullName').value = u.full_name || u.username;
  document.getElementById('editStaffRole').value = u.role;
  document.getElementById('editStaffActive').checked = Boolean(u.active);
  document.getElementById('editStaffModalSubtitle').textContent = `Account: @${u.username} (ID: ${u.id})`;
  document.getElementById('editStaffNotice').hidden = true;
  document.getElementById('editStaffModal').classList.remove('hidden');
}

function closeEditStaffModal() {
  document.getElementById('editStaffModal').classList.add('hidden');
}

async function handleSaveEditStaff(e) {
  e.preventDefault();
  const id = document.getElementById('editStaffId').value;
  const fullName = document.getElementById('editStaffFullName').value.trim();
  const role = document.getElementById('editStaffRole').value;
  const active = document.getElementById('editStaffActive').checked;

  try {
    await API.patch('/api/admin/staff/' + id, {
      fullName,
      role,
      active
    });
    closeEditStaffModal();
    await loadUsers();
  } catch (err) {
    setNotice('#editStaffNotice', err.message);
  }
}

function openResetStaffPwModal(id, username) {
  document.getElementById('resetStaffPwId').value = id;
  document.getElementById('resetStaffPwSubtitle').textContent = `Resetting password for @${username}`;
  document.getElementById('newStaffPassword').value = '';
  document.getElementById('confirmStaffPassword').value = '';
  document.getElementById('resetStaffPwNotice').hidden = true;
  document.getElementById('resetStaffPwModal').classList.remove('hidden');
}

function closeResetStaffPwModal() {
  document.getElementById('resetStaffPwModal').classList.add('hidden');
}

async function handleSaveResetStaffPw(e) {
  e.preventDefault();
  const id = document.getElementById('resetStaffPwId').value;
  const pw = document.getElementById('newStaffPassword').value;
  const confirmPw = document.getElementById('confirmStaffPassword').value;

  if (pw.length < 12) {
    setNotice('#resetStaffPwNotice', 'Password must be at least 12 characters.');
    return;
  }
  if (pw !== confirmPw) {
    setNotice('#resetStaffPwNotice', 'Passwords do not match. Please re-enter.');
    return;
  }

  try {
    await API.post('/api/admin/staff/' + id + '/reset-password', { password: pw });
    closeResetStaffPwModal();
    alert('Password updated successfully for staff account.');
  } catch (err) {
    setNotice('#resetStaffPwNotice', err.message);
  }
}

// ============================================================================
// STAFF WORK & HISAB LEDGER
// ============================================================================
let currentHisabStaffId = null;
let currentHisabData = null;

function populateHisabStaffSelect(staffList) {
  const sel = document.getElementById('hisabStaffSelect');
  if (!sel) return;
  const currentVal = sel.value;
  sel.innerHTML = '<option value="">-- Choose a staff member --</option>' +
    (staffList || []).map(u => `
      <option value="${u.id}">${escapeHTML(u.full_name || u.username)} (${escapeHTML(u.role)})</option>
    `).join('');
  if (currentVal && (staffList || []).some(u => String(u.id) === String(currentVal))) {
    sel.value = currentVal;
  }
}

async function handleHisabStaffChange(staffId) {
  currentHisabStaffId = staffId;
  const container = document.getElementById('hisabContentContainer');
  const emptyState = document.getElementById('hisabEmptyState');
  if (!staffId) {
    if (container) container.classList.add('hidden');
    if (emptyState) emptyState.classList.remove('hidden');
    return;
  }
  if (emptyState) emptyState.classList.add('hidden');
  if (container) container.classList.remove('hidden');
  await loadStaffHisab(staffId);
}

function openStaffHisab(staffId) {
  const sel = document.getElementById('hisabStaffSelect');
  if (sel) {
    sel.value = staffId;
  }
  handleHisabStaffChange(staffId);
  const sec = document.getElementById('staffHisabSection');
  if (sec) {
    sec.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

async function refreshHisabLedger() {
  if (currentHisabStaffId) {
    await loadStaffHisab(currentHisabStaffId);
  }
}

async function loadStaffHisab(staffId) {
  try {
    const data = await API.get('/api/admin/staff/' + staffId + '/ledger');
    currentHisabData = data;
    const staff = data.staff || {};

    const nameEl = document.getElementById('hisabStaffName');
    const roleEl = document.getElementById('hisabStaffRole');
    if (nameEl) nameEl.textContent = staff.full_name || staff.username || 'Staff Member';
    if (roleEl) roleEl.textContent = `${staff.role || 'TAILOR'} · Username: @${staff.username || ''} · Account ID: ${staff.id}`;

    const earnedEl = document.getElementById('hisabTotalEarned');
    const paidEl = document.getElementById('hisabTotalPaid');
    const remEl = document.getElementById('hisabRemainingBalance');
    if (earnedEl) earnedEl.textContent = money(data.totalEarned || 0);
    if (paidEl) paidEl.textContent = money(data.totalPaid || 0);
    if (remEl) {
      const rem = Number(data.remainingBalance || 0);
      remEl.textContent = money(rem);
      remEl.style.color = rem > 0 ? 'var(--orange)' : 'var(--green)';
    }

    const workStaffId = document.getElementById('workStaffId');
    if (workStaffId) workStaffId.value = staffId;
    const workDate = document.getElementById('workDate');
    if (workDate && !workDate.value) workDate.value = new Date().toISOString().slice(0, 10);

    // Render Work History Table
    const workTbody = document.getElementById('hisabWorkRows');
    const works = data.workHistory || [];
    if (workTbody) {
      workTbody.innerHTML = works.length ? works.map(w => {
        const totalCost = Number(w.total_cost != null ? w.total_cost : w.amount_owed) || 0;
        const totalPaid = Number(w.total_paid || 0);
        const remBalance = Number(w.remaining_balance != null ? w.remaining_balance : Math.max(0, totalCost - totalPaid));
        const status = remBalance <= 0 ? 'PAID' : (totalPaid > 0 ? 'PARTIAL' : 'UNPAID');
        const payments = w.payments || [];

        return `
          <tr>
            <td><strong>${w.work_date ? new Date(w.work_date).toLocaleDateString() : '—'}</strong></td>
            <td>
              <strong>${escapeHTML(w.description || '')}</strong>
              ${w.notes ? `<br><small class="muted">${escapeHTML(w.notes)}</small>` : ''}
              ${payments.length > 0 ? `
                <div style="margin-top:4px">
                  <small style="color:var(--muted)">📜 ${payments.length} payment${payments.length > 1 ? 's' : ''}: ${payments.map(p => `${money(p.amount)} on ${p.payment_date}`).join(', ')}</small>
                </div>
              ` : ''}
            </td>
            <td><strong>${money(totalCost)}</strong></td>
            <td style="color:var(--green)">${money(totalPaid)}</td>
            <td><strong style="color:${remBalance > 0 ? 'var(--orange)' : 'var(--green)'}">${money(remBalance)}</strong></td>
            <td>
              <span class="pill ${status === 'PAID' ? '' : 'orange'}">${status}</span>
            </td>
            <td style="white-space:nowrap">
              ${remBalance > 0 ? `
                <button class="btn orange btn-sm" data-work-pay-btn="true" type="button" onclick="openRecordStaffEntryPaymentModal('${w.id}', '${w.staff_id}', '${escapeHTML(w.description)}', ${totalCost}, ${totalPaid}, ${remBalance})">
                  💳 Record Pay
                </button>
              ` : '<span style="color:var(--green);font-size:12px;font-weight:700">✓ Settled</span>'}
            </td>
          </tr>
        `;
      }).join('') : '<tr><td colspan="7">No work entries recorded yet for this staff member.</td></tr>';
    }

    // Render Payment History Table
    const payTbody = document.getElementById('hisabPaymentRows');
    const payments = data.paymentHistory || [];
    if (payTbody) {
      payTbody.innerHTML = payments.length ? payments.map(p => `
        <tr>
          <td>${p.payment_date ? new Date(p.payment_date).toLocaleDateString() : '—'}</td>
          <td><strong style="color:var(--green)">${money(p.amount)}</strong></td>
          <td><span class="pill">${escapeHTML(p.payment_method || 'CASH')}</span></td>
          <td>
            ${escapeHTML(p.work_description ? 'Work: ' + p.work_description : (p.notes || '—'))}
            ${p.reference ? `<br><small class="muted">Ref: ${escapeHTML(p.reference)}</small>` : ''}
          </td>
          <td><small class="muted">${escapeHTML(p.recorded_by_user || 'Admin')}</small></td>
        </tr>
      `).join('') : '<tr><td colspan="5">No payment records found for this staff member.</td></tr>';
    }

  } catch (err) {
    console.error('Failed to load staff hisab:', err);
    alert('Error loading staff hisab: ' + err.message);
  }
}

function toggleRecordWorkCard() {
  const card = document.getElementById('recordWorkCard');
  if (card) {
    card.classList.toggle('hidden');
    if (!card.classList.contains('hidden')) {
      const wDate = document.getElementById('workDate');
      if (wDate) wDate.value = new Date().toISOString().slice(0, 10);
      const desc = document.getElementById('workDescription');
      if (desc) desc.focus();
      updateWorkBalancePreview();
    }
  }
}

function updateWorkBalancePreview() {
  const cost = Number(document.getElementById('workTotalCost')?.value) || 0;
  const pay = Number(document.getElementById('workPayNow')?.value) || 0;
  const prev = document.getElementById('workBalancePreview');
  const rem = Math.max(0, cost - pay);
  if (prev) {
    prev.textContent = money(rem);
    if (pay > cost) {
      prev.textContent = 'Pay Now exceeds Total Cost!';
      prev.style.color = '#b30000';
    } else {
      prev.style.color = rem > 0 ? 'var(--orange)' : 'var(--green)';
    }
  }
}

async function handleRecordStaffWork(e) {
  e.preventDefault();
  const staffId = document.getElementById('workStaffId').value;
  const workDate = document.getElementById('workDate').value;
  const description = document.getElementById('workDescription').value.trim();
  const totalCost = Number(document.getElementById('workTotalCost').value);
  const payNow = Number(document.getElementById('workPayNow').value) || 0;
  const paymentMethod = document.getElementById('workPayMethod').value;
  const notes = document.getElementById('workNotes').value.trim();

  if (!description) {
    setNotice('#workNotice', 'Work Done description is required.');
    return;
  }
  if (totalCost < 0) {
    setNotice('#workNotice', 'Total cost cannot be negative.');
    return;
  }
  if (payNow < 0) {
    setNotice('#workNotice', 'Pay Now cannot be negative.');
    return;
  }
  if (payNow > totalCost) {
    setNotice('#workNotice', `Pay Now (₹${payNow}) cannot exceed Total Cost (₹${totalCost}).`);
    return;
  }

  try {
    await API.post('/api/admin/staff/' + staffId + '/work', {
      workDate,
      description,
      totalCost,
      payNow,
      paymentMethod,
      notes
    });

    setNotice('#workNotice', `Work entry "${description}" saved successfully. Remaining balance: ${money(Math.max(0, totalCost - payNow))}.`, true);
    setTimeout(() => {
      document.getElementById('recordStaffWorkForm').reset();
      toggleRecordWorkCard();
      const n = document.getElementById('workNotice');
      if (n) n.hidden = true;
    }, 1500);

    await Promise.all([
      loadStaffHisab(staffId),
      loadUsers()
    ]);
  } catch (err) {
    setNotice('#workNotice', err.message);
  }
}

function openRecordStaffEntryPaymentModal(workId, staffId, description, totalCost, alreadyPaid, remainingDue) {
  document.getElementById('sepStaffId').value = staffId;
  document.getElementById('sepWorkId').value = workId;
  document.getElementById('sepSubtitle').textContent = `Work: ${description}`;
  document.getElementById('sepTotalCost').textContent = money(totalCost);
  document.getElementById('sepAlreadyPaid').textContent = money(alreadyPaid);
  document.getElementById('sepRemainingDue').textContent = money(remainingDue);

  const amtInput = document.getElementById('sepAmount');
  if (amtInput) {
    amtInput.value = Math.max(1, Number(remainingDue));
    amtInput.max = Number(remainingDue);
  }
  const dateInput = document.getElementById('sepDate');
  if (dateInput) dateInput.value = new Date().toISOString().slice(0, 10);
  const refInput = document.getElementById('sepReference');
  if (refInput) refInput.value = '';
  const noteInput = document.getElementById('sepNote');
  if (noteInput) noteInput.value = '';

  const notice = document.getElementById('sepNotice');
  if (notice) notice.hidden = true;

  document.getElementById('recordStaffEntryPaymentModal').classList.remove('hidden');
}

function closeRecordStaffEntryPaymentModal() {
  document.getElementById('recordStaffEntryPaymentModal').classList.add('hidden');
}

async function handleSaveStaffEntryPayment(e) {
  e.preventDefault();
  const staffId = document.getElementById('sepStaffId').value;
  const workId = document.getElementById('sepWorkId').value;
  const amount = Number(document.getElementById('sepAmount').value);
  const paymentDate = document.getElementById('sepDate').value;
  const paymentMethod = document.getElementById('sepMethod').value;
  const reference = document.getElementById('sepReference').value.trim();
  const notes = document.getElementById('sepNote').value.trim();

  if (!amount || amount <= 0) {
    setNotice('#sepNotice', 'Please specify a valid payment amount greater than zero.');
    return;
  }

  try {
    await API.post(`/api/admin/staff/${staffId}/work/${workId}/payments`, {
      amount,
      paymentDate,
      paymentMethod,
      reference,
      notes
    });

    closeRecordStaffEntryPaymentModal();
    await Promise.all([
      loadStaffHisab(staffId),
      loadUsers()
    ]);
  } catch (err) {
    setNotice('#sepNotice', err.message);
  }
}

// ============================================================================
// ADMIN ORDER MEASUREMENT SNAPSHOT MODAL & PRINTING
// ============================================================================
async function openAdminMeasurementModal(orderId) {
  const modal = document.getElementById('adminMeasurementModal');
  const title = document.getElementById('adminMeasureModalTitle');
  const subtitle = document.getElementById('adminMeasureModalSubtitle');
  const content = document.getElementById('adminMeasureModalContent');
  const printBtn = document.getElementById('printAdminMeasureBtn');

  content.innerHTML = '<p class="muted">Fetching measurement snapshot from database...</p>';
  modal.classList.remove('hidden');

  try {
    const res = await API.get('/api/admin/orders/' + orderId + '/measurement');
    currentViewedMeasurement = res;

    title.textContent = `Order: ${escapeHTML(res.orderNumber || '')}`;
    subtitle.textContent = `Customer: ${escapeHTML(res.customerName || '')}`;

    const mList = res.measurements && res.measurements.length ? res.measurements : (res.measurement ? [res.measurement] : []);

    if (!res.hasMeasurement || !mList.length) {
      printBtn.classList.add('hidden');
      content.innerHTML = `
        <div style="text-align:center;padding:28px 14px">
          <div style="font-size:42px">📐</div>
          <h4 style="margin:10px 0 4px;color:var(--violet)">No Measurement Profile Attached</h4>
          <p class="muted" style="max-width:440px;margin:0 auto;line-height:1.5">
            ${escapeHTML(res.message || 'No measurement snapshot was attached to this order (e.g. readymade item, standard alteration, or no profile was selected during order creation).')}
          </p>
        </div>
      `;
      return;
    }

    printBtn.classList.remove('hidden');

    content.innerHTML = mList.map(m => {
      let dims = {};
      try {
        dims = typeof m.dimensions === 'string' ? JSON.parse(m.dimensions) : (m.dimensions || {});
      } catch (e) {
        dims = {};
      }

      const createdStr = m.created_at
        ? new Date(m.created_at).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'short' }) + ' IST'
        : '—';

      const dimRows = Object.entries(dims).map(([k, v]) => `
        <tr>
          <td style="font-weight:600">${escapeHTML(ADMIN_SLIP_LABELS[k] || k.replace(/_/g, ' '))}</td>
          <td style="font-weight:700;color:var(--violet)">${escapeHTML(String(v))} ${escapeHTML(m.unit || 'in')}</td>
        </tr>
      `).join('') || '<tr><td colspan="2" class="muted">No specific dimensions entered.</td></tr>';

      return `
        <div class="measurement-box" style="margin-bottom:16px">
          <div style="display:flex;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;gap:8px">
            <div>
              <span class="eyebrow" style="color:var(--violet)">Garment Type</span>
              <h3 style="margin:2px 0 0;color:var(--violet);font-size:20px">${escapeHTML(m.garment_type)}</h3>
              <div class="muted" style="font-size:12px;margin-top:2px">
                Unit: <strong>${escapeHTML(m.unit || 'in')}</strong> · Fit: <strong>${escapeHTML(m.fit_preference || 'Standard Regular')}</strong>
              </div>
            </div>
            <div style="text-align:right">
              <span class="pill" style="font-size:11px">Recorded ${createdStr}</span>
              ${m.recorded_by ? `<div class="muted" style="font-size:12px;margin-top:4px">By: ${escapeHTML(m.recorded_by)}</div>` : ''}
            </div>
          </div>

          <table class="dim-table" style="margin-top:14px">
            <thead>
              <tr><th>Measurement Point (Slip)</th><th>Value</th></tr>
            </thead>
            <tbody>
              ${dimRows}
            </tbody>
          </table>

          ${m.notes ? `
            <div style="margin-top:12px;padding:10px 12px;background:#fff;border:1px dashed #d9bcd7;border-radius:8px">
              <strong style="font-size:12px;color:var(--violet)">Fitting / Tailor Notes:</strong>
              <p style="margin:4px 0 0;font-size:13px;line-height:1.4">${escapeHTML(m.notes)}</p>
            </div>
          ` : ''}
        </div>
      `;
    }).join('');
  } catch (err) {
    content.innerHTML = `<div class="notice error">Failed to load measurement snapshot: ${escapeHTML(err.message)}</div>`;
  }
}

function closeAdminMeasureModal() {
  document.getElementById('adminMeasurementModal').classList.add('hidden');
}

function printAdminMeasurement() {
  if (!currentViewedMeasurement) return;
  const res = currentViewedMeasurement;
  const mList = res.measurements && res.measurements.length ? res.measurements : (res.measurement ? [res.measurement] : []);
  if (!mList.length) return;

  const w = window.open('', '_blank');
  w.document.write(`
    <!doctype html>
    <html>
    <head>
      <meta charset="utf-8">
      <title>Measurements - ${escapeHTML(res.orderNumber || '')}</title>
      <style>
        body { font-family: Inter, Arial, sans-serif; padding: 24px; color: #222; max-width: 700px; margin: 0 auto; }
        .header { text-align: center; border-bottom: 2px solid #400849; padding-bottom: 12px; margin-bottom: 18px; }
        h1 { margin: 0; color: #400849; font-family: Georgia, serif; font-size: 24px; }
        .meta { display: flex; justify-content: space-between; margin-bottom: 16px; font-size: 14px; }
        table { width: 100%; border-collapse: collapse; margin-top: 8px; margin-bottom: 14px; }
        th, td { border: 1px solid #ccc; padding: 8px 12px; text-align: left; }
        th { background: #fbf2f9; color: #400849; }
        .notes { margin-top: 10px; padding: 10px; border: 1px dashed #786b7a; border-radius: 8px; font-size: 13px; }
        .section-title { font-size: 16px; font-weight: 700; color: #400849; margin: 16px 0 6px 0; border-bottom: 1px solid #ddd; padding-bottom: 4px; }
      </style>
    </head>
    <body>
      <div class="header">
        <h1>Jolly Ladies Tailor — Cutting & Measurement Slip</h1>
        <p style="margin:4px 0">Southern Avenue, Kolkata · Phone: 9748546778</p>
      </div>
      <div class="meta">
        <div>
          <p><strong>Order No:</strong> ${escapeHTML(res.orderNumber || '')}</p>
          <p><strong>Customer:</strong> ${escapeHTML(res.customerName || '')}</p>
        </div>
        <div style="text-align:right">
          <p><strong>Date:</strong> ${new Date().toLocaleDateString('en-IN')}</p>
        </div>
      </div>
      ${mList.map(m => {
        let dims = {};
        try { dims = typeof m.dimensions === 'string' ? JSON.parse(m.dimensions) : (m.dimensions || {}); } catch (_) {}
        return `
          <div class="section-title">📐 ${escapeHTML(m.garment_type)} (Unit: ${escapeHTML(m.unit || 'in')}, Fit: ${escapeHTML(m.fit_preference || 'Regular')})</div>
          <table>
            <thead>
              <tr><th>Measurement Point (Slip)</th><th>Value (${escapeHTML(m.unit || 'in')})</th></tr>
            </thead>
            <tbody>
              ${Object.entries(dims).map(([k, v]) => `
                <tr>
                  <td style="font-weight:600">${escapeHTML(ADMIN_SLIP_LABELS[k] || k.replace(/_/g, ' '))}</td>
                  <td><strong>${escapeHTML(String(v))} ${escapeHTML(m.unit || 'in')}</strong></td>
                </tr>
              `).join('')}
            </tbody>
          </table>
          ${m.notes ? `<div class="notes"><strong>Fitting Notes:</strong> ${escapeHTML(m.notes)}</div>` : ''}
        `;
      }).join('')}
      <p style="text-align:center;margin-top:24px;font-size:12px;color:#777">Printed for workshop tailoring & fitting verification.</p>
    </body>
    </html>
  `);
  w.document.close();
  w.focus();
  setTimeout(() => w.print(), 350);
}

// ============================================================================
// ADMIN CUSTOMER MEASUREMENTS HUB & EDITING
// ============================================================================
async function openAdminCustomerMeasurementsModal(customerId, customerName) {
  adminCurrentCustomerId = customerId;
  const modal = document.getElementById('adminCustomerMeasurementsModal');
  const title = document.getElementById('adminCustMeasureTitle');
  const container = document.getElementById('adminCustMeasurementsList');

  title.textContent = `Measurements for ${customerName}`;
  closeAdminCustMeasureForm();
  modal.classList.remove('hidden');

  await loadAdminCustomerMeasurements(customerId);
}

function closeAdminCustomerMeasurementsModal() {
  document.getElementById('adminCustomerMeasurementsModal').classList.add('hidden');
  adminCurrentCustomerId = null;
  adminCustomerMeasurements = [];
}

async function loadAdminCustomerMeasurements(customerId) {
  const container = document.getElementById('adminCustMeasurementsList');
  container.innerHTML = '<p class="muted">Fetching saved measurements from database...</p>';

  try {
    adminCustomerMeasurements = await API.get('/api/admin/customers/' + customerId + '/measurements');

    if (!adminCustomerMeasurements.length) {
      container.innerHTML = `
        <div style="text-align:center;padding:24px;background:#fcf8fc;border:1.5px dashed #ebd3e8;border-radius:12px">
          <div style="font-size:36px">📐</div>
          <h4 style="margin:8px 0 4px;color:var(--violet)">No Measurements on Record</h4>
          <p class="muted" style="margin:0 0 12px;font-size:13px">This customer does not have any saved measurement snapshots yet.</p>
          <button class="btn orange btn-sm" type="button" onclick="openAdminCustMeasureForm('CHURIDAR / BLOUSE')">＋ Take Slip Measurements Now</button>
        </div>
      `;
      return;
    }

    container.innerHTML = adminCustomerMeasurements.map(m => {
      const d = new Date(m.created_at).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
      let dims = {};
      try { dims = typeof m.dimensions === 'string' ? JSON.parse(m.dimensions) : (m.dimensions || {}); } catch (_) {}

      return `
        <div style="margin-bottom:14px;padding:14px;background:#fff;border:1.5px solid #ebd3e8;border-radius:10px">
          <div style="display:flex;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;gap:8px">
            <div>
              <strong style="color:var(--violet);font-size:16px">📐 ${escapeHTML(m.garment_type)}</strong>
              <div class="muted" style="font-size:12px;margin-top:2px">
                Recorded: <strong>${d}</strong> · Unit: <strong>${escapeHTML(m.unit || 'in')}</strong> · Fit: <strong>${escapeHTML(m.fit_preference || 'Regular')}</strong>
              </div>
            </div>
            <div style="display:flex;gap:6px">
              <button class="btn light btn-sm" type="button" onclick="editAdminCustomerMeasurement('${m.id}')">✏️ Edit</button>
              <a class="btn ghost btn-sm" href="/tailor.html?selectCustomer=${adminCurrentCustomerId}" title="Use this customer in Tailor Workspace">Use in Tailor ↗</a>
            </div>
          </div>

          <div style="display:grid;grid-template-columns:repeat(auto-fill, minmax(130px, 1fr));gap:8px;margin-top:10px">
            ${Object.entries(dims).map(([k, v]) => `
              <div style="background:#fcf5fb;padding:5px 8px;border-radius:6px;border:1px solid #ebd3e8;font-size:12px">
                <span class="muted" style="display:block;font-size:11px">${escapeHTML(ADMIN_SLIP_LABELS[k] || k)}</span>
                <strong style="color:var(--violet)">${escapeHTML(String(v))} ${escapeHTML(m.unit || 'in')}</strong>
              </div>
            `).join('')}
          </div>

          ${m.notes ? `<div class="muted" style="font-size:12px;margin-top:8px;font-style:italic">Notes: ${escapeHTML(m.notes)}</div>` : ''}
        </div>
      `;
    }).join('');
  } catch (err) {
    container.innerHTML = `<div class="notice error">Failed to load measurements: ${escapeHTML(err.message)}</div>`;
  }
}

function openAdminCustMeasureForm(garment = 'CHURIDAR / BLOUSE', existingDims = {}, fit = 'Regular', notes = '', editId = null) {
  const formCard = document.getElementById('adminCustMeasureFormCard');
  document.getElementById('adminCustMeasureEditId').value = editId || '';
  document.getElementById('adminCustMeasureGarment').value = garment;
  document.getElementById('adminCustMeasureFit').value = fit || 'Regular';
  document.getElementById('adminCustMeasureNotes').value = notes || '';

  const heading = document.getElementById('adminCustMeasureFormHeading');
  if (heading) heading.textContent = editId ? `Edit ${garment} Profile` : `Record ${garment} Measurement Profile`;
  const submitBtn = document.getElementById('adminCustMeasureSubmitBtn');
  if (submitBtn) submitBtn.textContent = editId ? '💾 Update Measurement Profile' : '💾 Save Measurement Profile';

  drawAdminCustDynamicFields(garment, existingDims);
  formCard.classList.remove('hidden');
  formCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeAdminCustMeasureForm() {
  const formCard = document.getElementById('adminCustMeasureFormCard');
  if (formCard) formCard.classList.add('hidden');
  const form = document.getElementById('adminCustMeasureForm');
  if (form) form.reset();
  const notice = document.getElementById('adminCustMeasureNotice');
  if (notice) notice.hidden = true;
}

function drawAdminCustDynamicFields(garment, existingValues = {}) {
  const def = ADMIN_MEASUREMENT_DEFINITIONS[garment] || ADMIN_MEASUREMENT_DEFINITIONS['CHURIDAR / BLOUSE'] || ADMIN_MEASUREMENT_DEFINITIONS['General body measurements'];
  const unit = val('adminCustMeasureUnit') === 'in' ? 'Inches' : 'cm';
  const container = document.getElementById('adminCustDynamicFields');
  if (!container) return;

  if (Array.isArray(def)) {
    container.style.display = 'grid';
    container.style.gridTemplateColumns = 'repeat(auto-fill, minmax(130px, 1fr))';
    container.style.gap = '10px';
    container.innerHTML = def.map(k => {
      const val = existingValues[k] != null ? existingValues[k] : '';
      return `
        <div class="field" style="margin:0">
          <label for="admin_dim_${k}" style="font-size:12px;font-weight:600">${escapeHTML(ADMIN_SLIP_LABELS[k] || k)}</label>
          <input id="admin_dim_${k}" data-admin-measure-key="${k}" type="text" inputmode="decimal"
                 placeholder="${unit}" value="${escapeHTML(val)}" style="padding:6px 8px;font-size:13px">
        </div>
      `;
    }).join('');
  } else if (typeof def === 'object' && def !== null) {
    container.style.display = 'block';
    container.innerHTML = Object.entries(def).map(([grpName, grpKeys]) => `
      <div style="background:#fff;border:1px solid #ebd3e8;border-radius:8px;padding:12px;margin-bottom:10px">
        <strong style="color:var(--violet);font-size:13px;display:block;margin-bottom:8px">📐 ${escapeHTML(grpName)} Dimensions (Slip)</strong>
        <div style="display:grid;grid-template-columns:repeat(auto-fill, minmax(130px, 1fr));gap:8px">
          ${grpKeys.map(k => {
            const compoundKey = grpName.includes('PANT') ? 'pant_' + k : k;
            const val = existingValues[compoundKey] != null ? existingValues[compoundKey]
                      : (existingValues[k] != null ? existingValues[k] : '');
            return `
              <div class="field" style="margin:0">
                <label for="admin_dim_${compoundKey}" style="font-size:12px;font-weight:600">${escapeHTML(ADMIN_SLIP_LABELS[k] || k)}</label>
                <input id="admin_dim_${compoundKey}" data-admin-measure-key="${compoundKey}" type="text" inputmode="decimal"
                       placeholder="${unit}" value="${escapeHTML(val)}" style="padding:6px 8px;font-size:13px">
              </div>
            `;
          }).join('')}
        </div>
      </div>
    `).join('');
  }
}

function editAdminCustomerMeasurement(snapshotId) {
  const snap = adminCustomerMeasurements.find(m => m.id === snapshotId);
  if (!snap) return;
  let dims = {};
  try { dims = typeof snap.dimensions === 'string' ? JSON.parse(snap.dimensions) : (snap.dimensions || {}); } catch (_) {}
  openAdminCustMeasureForm(snap.garment_type, dims, snap.fit_preference, snap.notes, snap.id);
}

async function handleSaveAdminCustomerMeasurement(e) {
  e.preventDefault();
  if (!adminCurrentCustomerId) return;

  const editId = val('adminCustMeasureEditId');
  const garment = val('adminCustMeasureGarment');
  const unit = val('adminCustMeasureUnit');
  const fitPreference = val('adminCustMeasureFit');
  const notes = val('adminCustMeasureNotes');

  const dimensions = {};
  document.querySelectorAll('[data-admin-measure-key]').forEach(inp => {
    const v = inp.value.trim();
    if (v) dimensions[inp.dataset.adminMeasureKey] = v;
  });

  const notice = document.getElementById('adminCustMeasureNotice');
  if (!Object.keys(dimensions).length) {
    notice.textContent = 'Please enter at least one measurement dimension.';
    notice.className = 'notice error';
    notice.hidden = false;
    return;
  }

  const submitBtn = document.getElementById('adminCustMeasureSubmitBtn');
  try {
    submitBtn.disabled = true;
    submitBtn.textContent = 'Saving Profile...';

    if (editId) {
      await API.put(`/api/admin/customers/${adminCurrentCustomerId}/measurements/${editId}`, {
        garmentType: garment,
        unit,
        dimensions,
        fitPreference,
        notes
      });
    } else {
      await API.post(`/api/admin/customers/${adminCurrentCustomerId}/measurements`, {
        garmentType: garment,
        unit,
        dimensions,
        fitPreference,
        notes
      });
    }

    closeAdminCustMeasureForm();
    await loadAdminCustomerMeasurements(adminCurrentCustomerId);
  } catch (err) {
    notice.textContent = 'Failed to save measurement: ' + err.message;
    notice.className = 'notice error';
    notice.hidden = false;
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = editId ? '💾 Update Measurement Profile' : '💾 Save Measurement Profile';
  }
}

// ============================================================================
// SHOP SETTINGS & GPAY QR
// ============================================================================
window.openAdminShopSettingsModal = function() {
  const m = document.getElementById('adminShopSettingsModal');
  if (m) {
    m.classList.remove('hidden');
    loadShopSettings();
  }
};

window.closeAdminShopSettingsModal = function() {
  const m = document.getElementById('adminShopSettingsModal');
  if (m) {
    m.classList.add('hidden');
  }
};

async function loadShopSettings() {
  try {
    const s = await API.get('/api/admin/shop-settings');
    const nameEl = document.getElementById('shopName');
    if (!nameEl) return;
    nameEl.value = s.shop_name || 'Jolly Ladies Tailor';
    document.getElementById('shopPhone').value = s.shop_phone || '';
    document.getElementById('shopAddress').value = s.shop_address || '';
    document.getElementById('shopWhatsapp').value = s.whatsapp_number || '';
    document.getElementById('shopAdminPhone').value = s.admin_notification_phone || '';
    document.getElementById('shopUpi').value = s.upi_id || '';

    if (s.gpay_qr_key) {
      const pub = await API.get('/api/public/shop');
      if (pub.gpayQrUrl) {
        const img = document.getElementById('shopQrPreview');
        if (img) {
          img.src = pub.gpayQrUrl;
          img.classList.remove('hidden');
        }
      }
    }
  } catch (e) {
    setNotice('#shopNotice', e.message);
  }
}

async function saveShopSettings(e) {
  e.preventDefault();
  try {
    await API.request('/api/admin/shop-settings', {
      method: 'PUT',
      body: JSON.stringify({
        shop_name: av('shopName'),
        shop_phone: av('shopPhone'),
        shop_address: av('shopAddress'),
        whatsapp_number: av('shopWhatsapp'),
        admin_notification_phone: av('shopAdminPhone'),
        upi_id: av('shopUpi')
      })
    });
    setNotice('#shopNotice', 'Shop details saved successfully.', true);
  } catch (err) {
    setNotice('#shopNotice', err.message);
  }
}

async function uploadShopQr() {
  const file = document.getElementById('shopQr').files[0];
  if (!file) {
    setNotice('#shopNotice', 'Please choose a QR image file first.');
    return;
  }
  const fd = new FormData();
  fd.append('file', file);
  try {
    const result = await API.post('/api/admin/media/shop/gpay-qr', fd);
    const img = document.getElementById('shopQrPreview');
    if (img) {
      img.src = result.url;
      img.classList.remove('hidden');
    }
    setNotice('#shopNotice', 'GPay QR code uploaded. It is now visible on customer bills and website.', true);
  } catch (e) {
    setNotice('#shopNotice', e.message);
  }
}

function logout() {
  API.post('/api/auth/logout', {}).finally(() => location.reload());
}

// Event Bindings
document.getElementById('loginForm').addEventListener('submit', login);
const adminUrlParams = new URLSearchParams(window.location.search);
if ((window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') && adminUrlParams.get('autologin') === 'admin') {
  const u = document.getElementById('username');
  const p = document.getElementById('password');
  if (u && p) {
    u.value = 'admin';
    p.value = 'AdminPassword123!';
    login(new Event('submit'));
  }
}
document.getElementById('logout').addEventListener('click', logout);
document.getElementById('range').addEventListener('change', loadStats);
document.getElementById('productForm').addEventListener('submit', handleProductSubmit);
document.getElementById('categoryForm').addEventListener('submit', addCategory);
document.getElementById('userForm').addEventListener('submit', addUser);
document.getElementById('refreshProducts').addEventListener('click', () => Promise.all([loadProducts(), loadCategories()]));
document.getElementById('refreshAdjustments').addEventListener('click', loadPriceAdjustments);
const refReq = document.getElementById('refreshRequests');
if (refReq) refReq.addEventListener('click', loadRequests);
const shopForm = document.getElementById('shopSettingsForm');
if (shopForm) shopForm.addEventListener('submit', saveShopSettings);
const shopQrBtn = document.getElementById('uploadShopQr');
if (shopQrBtn) shopQrBtn.addEventListener('click', uploadShopQr);

document.getElementById('adminCustomerSearch').addEventListener('input', e => {
  loadAdminCustomers(e.target.value.trim());
});

document.getElementById('adminPaymentSearch').addEventListener('input', e => {
  loadAdminPayments(e.target.value.trim());
});

document.getElementById('closeCustomerOrders').addEventListener('click', () => {
  document.getElementById('adminCustomerOrdersDetail').classList.add('hidden');
});

// Auto-session check on page load
(async () => {
  try {
    const me = await API.get('/api/auth/me');
    if (me.role === 'ADMIN') {
      await showAdmin(me);
    }
  } catch (e) {
    /* Anonymous visitor */
  }
})();

// ============================================================================
// EXCEL FULL DATABASE EXPORT WORKFLOW
// ============================================================================
async function exportAllRecordsToExcel() {
  const topBtn = document.getElementById('btnExportAllExcelTop');
  const noticeEl = document.getElementById('excelExportNotice');
  const originalTopText = topBtn ? topBtn.textContent : '';

  if (topBtn) {
    topBtn.disabled = true;
    topBtn.textContent = '⏳ Exporting All Records...';
  }
  if (noticeEl) {
    noticeEl.textContent = 'Generating comprehensive Excel workbook from current database...';
    noticeEl.className = 'notice';
    noticeEl.hidden = false;
  }

  try {
    const res = await fetch('/api/admin/reports/export-excel', {
      method: 'GET',
      credentials: 'same-origin'
    });

    if (!res.ok) {
      const errText = await res.text();
      throw new Error(errText || `Server returned ${res.status}`);
    }

    const blob = await res.blob();
    let filename = `jolly-ladies-tailor-export-${new Date().toISOString().slice(0, 10)}.xlsx`;
    const disposition = res.headers.get('content-disposition');
    if (disposition && disposition.includes('filename=')) {
      const match = disposition.match(/filename="?([^";]+)"?/);
      if (match && match[1]) filename = match[1];
    }

    const downloadUrl = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    window.URL.revokeObjectURL(downloadUrl);

    if (noticeEl) {
      noticeEl.textContent = `✅ Successfully exported all store records to ${filename}!`;
      noticeEl.className = 'notice success';
      setTimeout(() => { if (noticeEl) noticeEl.hidden = true; }, 6000);
    }
  } catch (err) {
    console.error('Excel export failed:', err);
    if (noticeEl) {
      noticeEl.textContent = 'Failed to export Excel workbook: ' + (err.message || 'Unknown error');
      noticeEl.className = 'notice error';
    }
  } finally {
    if (topBtn) {
      topBtn.disabled = false;
      topBtn.textContent = originalTopText || '📊 Export all records to Excel';
    }
  }
}

// ============================================================================
// ADMIN CUSTOMER MANAGEMENT (INTERCONNECTED WORKSPACE)
// ============================================================================
function openAdminNewCustomerModal() {
  document.getElementById('adminCustomerId').value = '';
  document.getElementById('adminCustName').value = '';
  document.getElementById('adminCustPhone').value = '';
  document.getElementById('adminCustAddress').value = '';
  document.getElementById('adminCustNotes').value = '';
  document.getElementById('adminCustomerModalTitle').textContent = 'Register Customer';
  document.getElementById('adminCustomerSubmitBtn').textContent = 'Create Customer';
  document.getElementById('adminCustomerNotice').hidden = true;
  document.getElementById('adminCustomerModal').classList.remove('hidden');
}

function openAdminEditCustomerModal(cust) {
  document.getElementById('adminCustomerId').value = cust.id;
  document.getElementById('adminCustName').value = cust.name || '';
  document.getElementById('adminCustPhone').value = cust.phone || '';
  document.getElementById('adminCustAddress').value = cust.address || '';
  document.getElementById('adminCustNotes').value = cust.notes || '';
  document.getElementById('adminCustomerModalTitle').textContent = 'Edit Customer Profile';
  document.getElementById('adminCustomerSubmitBtn').textContent = 'Save Changes';
  document.getElementById('adminCustomerNotice').hidden = true;
  document.getElementById('adminCustomerModal').classList.remove('hidden');
}

function closeAdminCustomerModal() {
  document.getElementById('adminCustomerModal').classList.add('hidden');
}

async function handleSaveAdminCustomer(e) {
  e.preventDefault();
  const id = document.getElementById('adminCustomerId').value;
  const name = document.getElementById('adminCustName').value.trim();
  const phone = document.getElementById('adminCustPhone').value.trim();
  const address = document.getElementById('adminCustAddress').value.trim();
  const notes = document.getElementById('adminCustNotes').value.trim();

  if (!name || !phone) {
    setNotice('#adminCustomerNotice', 'Customer name and phone number are required.');
    return;
  }

  try {
    const payload = { name, phone, address, notes };
    if (id) {
      await API.patch('/api/admin/customers/' + id, payload);
    } else {
      await API.post('/api/admin/customers', payload);
    }
    closeAdminCustomerModal();
    await loadAdminCustomers(document.getElementById('adminCustomerSearch') ? document.getElementById('adminCustomerSearch').value : '');
    await loadStats();
  } catch (err) {
    setNotice('#adminCustomerNotice', err.message);
  }
}

// ============================================================================
// ADMIN ORDER STATUS & PAYMENT WORKFLOW
// ============================================================================
async function openAdminOrderPaymentModal(orderId, total, paid, due, orderNum, custName) {
  document.getElementById('adminPayOrderId').value = orderId;
  document.getElementById('adminPayModalSubtitle').textContent = `Order ${orderNum} · Customer: ${custName}`;
  document.getElementById('adminPayTotal').textContent = money(total);
  document.getElementById('adminPayPaid').textContent = money(paid);
  document.getElementById('adminPayDue').textContent = money(due);
  document.getElementById('adminPayAmount').value = Math.max(1, Math.round(Number(due)));
  document.getElementById('adminPayAmount').max = Math.round(Number(due));
  document.getElementById('adminPayReference').value = '';
  document.getElementById('adminPayNote').value = '';
  document.getElementById('adminPayNotice').hidden = true;
  document.getElementById('adminOrderPaymentModal').classList.remove('hidden');
}

function closeAdminOrderPaymentModal() {
  document.getElementById('adminOrderPaymentModal').classList.add('hidden');
}

async function handleSaveAdminOrderPayment(e) {
  e.preventDefault();
  const orderId = document.getElementById('adminPayOrderId').value;
  const amount = Number(document.getElementById('adminPayAmount').value);
  const method = document.getElementById('adminPayMethod').value;
  const reference = document.getElementById('adminPayReference').value.trim();
  const note = document.getElementById('adminPayNote').value.trim();

  if (!amount || amount <= 0) {
    setNotice('#adminPayNotice', 'Please specify a valid payment amount.');
    return;
  }

  try {
    await API.post(`/api/admin/orders/${orderId}/payments`, {
      amount,
      method,
      reference,
      note
    });
    closeAdminOrderPaymentModal();
    await Promise.all([
      loadStats(),
      loadAdminPayments(),
      loadAdminCustomers(document.getElementById('adminCustomerSearch') ? document.getElementById('adminCustomerSearch').value : '')
    ]);
  } catch (err) {
    setNotice('#adminPayNotice', err.message);
  }
}

async function handleAdminUpdateOrderStatus(orderId, newStatus) {
  try {
    await API.patch(`/api/admin/orders/${orderId}/status`, { status: newStatus });
    await loadStats();
  } catch (err) {
    alert('Failed to update order status: ' + err.message);
    await loadStats();
  }
}
