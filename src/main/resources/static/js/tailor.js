// ============================================================================
// Jolly Ladies Tailor — Tailor Workspace (Customer-First Redesign)
// ============================================================================

const measurementDefinitions = {
  'Blouse': ['shoulder','bust','upperBust','blouseWaist','blouseLength','sleeveLength','armhole','biceps','wrist','frontNeck','backNeck','apexDistance'],
  'Kurti': ['shoulder','bust','waist','hip','armhole','sleeveLength','kurtiLength','neckDepth'],
  'Salwar / Kameez': ['shoulder','bust','waist','hip','kameezLength','sleeveLength','armhole','neckDepth','salwarLength','thigh','ankle'],
  'Pant': ['waist','hip','rise','thigh','knee','bottom','inseam','outseam','pantLength'],
  'Frock': ['shoulder','bust','waist','hip','armhole','sleeveLength','frockLength','neckDepth'],
  'Lehenga': ['waist','hip','lehengaLength','flare','waistBand'],
  'Alteration': ['shoulder','bust','waist','hip','sleeveLength','lengthToAdjust','waistToAdjust','notesMeasurement'],
  'False Par & Peko': ['sareeLength','sareeWidth','parLength','pekoEdges','notesMeasurement'],
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

const ORDER_STATUS_LABELS = {
  'NEW': 'New',
  'IN_PROGRESS': 'In Progress',
  'TRIAL': 'Trial',
  'READY': 'Ready',
  'DELIVERED': 'Delivered',
  'CANCELLED': 'Cancelled'
};

// Workspace State
let currentCustomer = null;
let customerMeasurements = [];
let customerOrders = [];
let shopOrders = [];
let products = [];
let cart = [];
let editingOrderId = null;
let selectedGarment = 'Blouse';
let shopSettings = {};
let paymentQrData = {};
let searchDebounceTimer = null;

const val = id => {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
};

// ============================================================================
// Authentication
// ============================================================================
async function doLogin(event) {
  event.preventDefault();
  const username = val('username');
  const password = document.getElementById('password') ? document.getElementById('password').value : '';
  if (!username || !password) {
    setNotice('#loginNotice', 'Please enter your username and password.');
    return;
  }
  try {
    await API.csrf();
    const who = await API.post('/api/auth/login', { username, password });
    if (!['ADMIN', 'TAILOR'].includes(who.role)) {
      throw new Error('This account does not have tailor workspace permissions.');
    }
    document.getElementById('whoami').textContent = `${who.username} · ${who.role}`;
    document.getElementById('loginPanel').classList.add('hidden');
    document.getElementById('workspace').classList.remove('hidden');
    document.getElementById('logout').classList.remove('hidden');
    if (document.getElementById('password')) document.getElementById('password').value = '';
    await loadInitialWorkspace();
  } catch (e) {
    setNotice('#loginNotice', e.message);
  }
}

function logout() {
  API.post('/api/auth/logout', {}).finally(() => location.reload());
}

// ============================================================================
// Initial Workspace Load
// ============================================================================
async function loadInitialWorkspace() {
  try {
    shopSettings = await API.get('/api/public/shop').catch(() => ({}));
    await Promise.all([
      loadPaymentQr(),
      loadProducts(),
      loadRecentShopOrders(),
      searchCustomers('')
    ]);
  } catch (e) {
    console.warn('Workspace initialization warning:', e);
  }
}

// ============================================================================
// STEP 1: Customer Search & Registration
// ============================================================================
async function searchCustomers(q = '') {
  try {
    const list = await API.get('/api/tailor/customers' + (q ? '?q=' + encodeURIComponent(q) : ''));
    renderCustomerSearchResults(list, q);
  } catch (err) {
    console.error('Customer search failed:', err);
  }
}

function renderCustomerSearchResults(list, query) {
  const grid = document.getElementById('customerResultsGrid');
  const countBadge = document.getElementById('searchResultsCount');
  countBadge.textContent = `${list.length} found`;

  if (!list.length) {
    grid.innerHTML = `
      <div style="grid-column:1/-1;background:#fff;border:1.5px dashed #ebd3e8;border-radius:14px;padding:24px;text-align:center">
        <p class="muted" style="margin:0 0 12px 0">No customer found matching "<strong>${escapeHTML(query)}</strong>".</p>
        <button class="btn orange btn-sm" type="button" onclick="openNewCustomerForm('${escapeHTML(query)}')">＋ Add as New Customer</button>
      </div>`;
    return;
  }

  grid.innerHTML = list.map(c => `
    <div class="customer-card ${currentCustomer?.id === c.id ? 'selected' : ''}" onclick="selectCustomer('${c.id}')">
      <div style="display:flex;justify-content:space-between;align-items:flex-start">
        <h3>${escapeHTML(c.name)}</h3>
        <span class="pill">Select ›</span>
      </div>
      <div class="phone">📱 ${escapeHTML(c.phone)}</div>
      ${c.address ? `<div class="notes">📍 ${escapeHTML(c.address)}</div>` : ''}
      ${c.notes ? `<div class="notes" style="font-style:italic">💬 ${escapeHTML(c.notes)}</div>` : ''}
    </div>
  `).join('');
}

function openNewCustomerForm(initialNameOrPhone = '') {
  const card = document.getElementById('newCustomerCard');
  card.classList.remove('hidden');
  const isDigits = /^\d+$/.test(initialNameOrPhone.replace(/\D/g, ''));
  if (isDigits && initialNameOrPhone.length >= 7) {
    document.getElementById('newCustPhone').value = initialNameOrPhone;
    document.getElementById('newCustName').focus();
  } else if (initialNameOrPhone) {
    document.getElementById('newCustName').value = initialNameOrPhone;
    document.getElementById('newCustPhone').focus();
  } else {
    document.getElementById('newCustName').focus();
  }
  card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeNewCustomerForm() {
  document.getElementById('newCustomerCard').classList.add('hidden');
  document.getElementById('newCustomerForm').reset();
  document.getElementById('duplicatePhoneWarning').hidden = true;
  document.getElementById('newCustNotice').hidden = true;
}

// Live Duplicate Phone Check
async function checkDuplicatePhone(phone) {
  const warn = document.getElementById('duplicatePhoneWarning');
  const clean = phone.replace(/[^0-9+]/g, '');
  if (clean.length < 10) {
    warn.hidden = true;
    return;
  }
  try {
    const existing = await API.get('/api/tailor/customers/by-phone?phone=' + encodeURIComponent(clean));
    if (existing && existing.id) {
      warn.innerHTML = `
        <strong>⚠️ Customer Already Registered:</strong> ${escapeHTML(existing.name)} (${escapeHTML(existing.phone)}) is already saved.
        <button class="btn light btn-sm" type="button" style="margin-left:10px" onclick="selectCustomer('${existing.id}')">Open Existing Customer</button>
      `;
      warn.className = 'notice';
      warn.hidden = false;
    } else {
      warn.hidden = true;
    }
  } catch (_) {
    warn.hidden = true;
  }
}

async function handleRegisterCustomer(e) {
  e.preventDefault();
  const name = val('newCustName');
  const phone = val('newCustPhone');
  const address = val('newCustAddress');
  const notes = val('newCustNotes');

  if (!name || !phone) {
    setNotice('#newCustNotice', 'Name and mobile number are required.');
    return;
  }

  try {
    const saved = await API.post('/api/tailor/customers', { name, phone, address, notes });
    closeNewCustomerForm();
    await selectCustomer(saved.id);
  } catch (err) {
    setNotice('#newCustNotice', err.message);
  }
}

// ============================================================================
// STEP 2: Customer Hub & Active Record Management
// ============================================================================
async function selectCustomer(id) {
  if (!id) return;
  try {
    currentCustomer = await API.get('/api/tailor/customers/' + id);
    closeNewCustomerForm();

    // Switch step views
    document.getElementById('step1View').classList.add('hidden');
    document.getElementById('step2View').classList.remove('hidden');
    document.getElementById('switchCustomerTopBtn').classList.remove('hidden');

    document.getElementById('stepNav1').classList.remove('active');
    document.getElementById('stepNav1').classList.add('completed');
    document.getElementById('stepNav2').classList.add('active');

    // Populate Customer Summary Card
    document.getElementById('hubCustomerName').textContent = currentCustomer.name;
    document.getElementById('hubCustomerPhone').textContent = currentCustomer.phone;
    document.getElementById('hubCustomerAddress').textContent = currentCustomer.address ? `📍 ${currentCustomer.address}` : 'No address saved';
    document.getElementById('hubCustomerNotes').textContent = currentCustomer.notes ? `Preferences: ${currentCustomer.notes}` : '';

    const digits = whatsappDigits(currentCustomer.phone);
    const waLink = document.getElementById('hubCustomerWaLink');
    if (digits) {
      waLink.href = `https://wa.me/${digits}`;
      waLink.style.display = 'inline-flex';
    } else {
      waLink.style.display = 'none';
    }

    // Set labels on other tabs
    document.querySelectorAll('.customer-name-label').forEach(el => el.textContent = currentCustomer.name);

    // Load related customer data in parallel
    await Promise.all([
      loadCustomerMeasurements(currentCustomer.id),
      loadCustomerOrders(currentCustomer.id)
    ]);

    // Activate default Overview Tab
    switchCustomerTab('overview');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  } catch (err) {
    setNotice('#workspaceNotice', 'Failed to load customer: ' + err.message);
  }
}

function switchBackToCustomerSearch() {
  document.getElementById('step2View').classList.add('hidden');
  document.getElementById('step1View').classList.remove('hidden');
  document.getElementById('switchCustomerTopBtn').classList.add('hidden');

  document.getElementById('stepNav2').classList.remove('active');
  document.getElementById('stepNav3').classList.remove('active');
  document.getElementById('stepNav1').classList.add('active');
  document.getElementById('stepNav1').classList.remove('completed');

  searchCustomers(val('customerSearchInput'));
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

// Inline Edit Customer Details
function openEditCustomerBox() {
  if (!currentCustomer) return;
  document.getElementById('editCustName').value = currentCustomer.name || '';
  document.getElementById('editCustPhone').value = currentCustomer.phone || '';
  document.getElementById('editCustAddress').value = currentCustomer.address || '';
  document.getElementById('editCustNotes').value = currentCustomer.notes || '';
  document.getElementById('editCustomerBox').classList.remove('hidden');
}

function closeEditCustomerBox() {
  document.getElementById('editCustomerBox').classList.add('hidden');
  document.getElementById('editCustNotice').hidden = true;
}

async function handleSaveCustomerEdits(e) {
  e.preventDefault();
  if (!currentCustomer) return;
  const name = val('editCustName');
  const phone = val('editCustPhone');
  const address = val('editCustAddress');
  const notes = val('editCustNotes');

  try {
    const updated = await API.patch('/api/tailor/customers/' + currentCustomer.id, { name, phone, address, notes });
    currentCustomer = updated;
    document.getElementById('hubCustomerName').textContent = currentCustomer.name;
    document.getElementById('hubCustomerPhone').textContent = currentCustomer.phone;
    document.getElementById('hubCustomerAddress').textContent = currentCustomer.address ? `📍 ${currentCustomer.address}` : 'No address saved';
    document.getElementById('hubCustomerNotes').textContent = currentCustomer.notes ? `Preferences: ${currentCustomer.notes}` : '';
    document.querySelectorAll('.customer-name-label').forEach(el => el.textContent = currentCustomer.name);
    closeEditCustomerBox();
    setNotice('#workspaceNotice', 'Customer details updated successfully in database.', true);
  } catch (err) {
    setNotice('#editCustNotice', err.message);
  }
}

// Tab Switching
function switchCustomerTab(tabName) {
  const tabs = ['overview', 'measurements', 'orders', 'payments'];
  tabs.forEach(t => {
    const btn = document.getElementById('tabBtn' + t.charAt(0).toUpperCase() + t.slice(1));
    const pane = document.getElementById('tabContent' + t.charAt(0).toUpperCase() + t.slice(1));
    if (btn) btn.classList.toggle('active', t === tabName);
    if (pane) pane.classList.toggle('hidden', t !== tabName);
  });

  if (tabName === 'orders' || tabName === 'overview') {
    document.getElementById('stepNav2').classList.remove('active');
    document.getElementById('stepNav2').classList.add('completed');
    document.getElementById('stepNav3').classList.add('active');
  } else {
    document.getElementById('stepNav3').classList.remove('active');
    document.getElementById('stepNav2').classList.add('active');
  }
}

// ============================================================================
// MEASUREMENTS: Hub, Intelligent Reuse, Snapshots
// ============================================================================
async function loadCustomerMeasurements(customerId) {
  try {
    customerMeasurements = await API.get('/api/tailor/customers/' + customerId + '/measurements');
    document.getElementById('measureCountBadge').textContent = customerMeasurements.length;

    // Latest measurement date
    if (customerMeasurements.length) {
      const latest = customerMeasurements[0];
      const d = new Date(latest.created_at);
      document.getElementById('metricLatestMeasureDate').textContent = `${latest.garment_type} (${d.toLocaleDateString('en-IN', {month:'short', day:'numeric'})})`;
    } else {
      document.getElementById('metricLatestMeasureDate').textContent = 'None yet';
    }

    renderGarmentSnapshot(selectedGarment);
    renderMeasurementsTimeline();
    renderOverviewMeasurementHighlights();
    populateOrderMeasurementDropdown();
  } catch (err) {
    console.error('Failed to load measurements:', err);
  }
}

function selectGarmentTab(garment) {
  selectedGarment = garment;
  document.querySelectorAll('.garment-tab-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.garment === garment);
  });
  renderGarmentSnapshot(garment);
}

function renderGarmentSnapshot(garment) {
  const display = document.getElementById('garmentSnapshotDisplay');
  const snapshots = customerMeasurements.filter(m => m.garment_type === garment);

  if (!snapshots.length) {
    display.innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px">
        <div>
          <h3 style="margin:0;color:var(--violet)">Latest ${escapeHTML(garment)} Measurements</h3>
          <p class="muted" style="margin:4px 0 0 0">No measurement snapshot recorded for ${escapeHTML(garment)} yet.</p>
        </div>
        <button class="btn light btn-sm" type="button" onclick="openMeasurementForm('${escapeHTML(garment)}')">＋ Record ${escapeHTML(garment)} Measurements</button>
      </div>`;
    return;
  }

  const latest = snapshots[0];
  let dims = {};
  try { dims = typeof latest.dimensions === 'string' ? JSON.parse(latest.dimensions) : (latest.dimensions || {}); } catch(_) {}

  const dateStr = new Date(latest.created_at).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

  display.innerHTML = `
    <div class="snapshot-card-header">
      <div>
        <h3 style="margin:0;color:var(--violet)">Latest ${escapeHTML(garment)} Snapshot</h3>
        <small class="muted">Recorded on ${dateStr} · Fit: <strong>${escapeHTML(latest.fit_preference || 'Regular')}</strong> · Unit: <strong>${escapeHTML(latest.unit)}</strong></small>
        ${latest.notes ? `<div style="font-size:12px;color:var(--violet);margin-top:4px">Notes: ${escapeHTML(latest.notes)}</div>` : ''}
      </div>
      <div style="display:flex;gap:8px">
        <button class="btn light btn-sm" type="button" onclick="copyAndEditMeasurement('${latest.id}')">📋 Copy & Edit</button>
        <button class="btn orange btn-sm" type="button" onclick="useSnapshotForNewOrder('${latest.id}')">✂️ Use in New Order</button>
        <button class="btn ghost btn-sm" type="button" onclick="openMeasurementForm('${escapeHTML(garment)}')">＋ Fresh</button>
      </div>
    </div>
    <div class="dimension-chips">
      ${Object.entries(dims).map(([k, v]) => `
        <div class="dim-chip"><span>${escapeHTML(labels[k] || k)}:</span> <strong>${escapeHTML(v)} ${escapeHTML(latest.unit)}</strong></div>
      `).join('')}
    </div>
  `;
}

function renderOverviewMeasurementHighlights() {
  const container = document.getElementById('overviewMeasurementsHighlights');
  if (!container) return;

  const popular = ['Blouse', 'Kurti', 'Salwar / Kameez', 'Pant'];
  container.innerHTML = popular.map(g => {
    const found = customerMeasurements.find(m => m.garment_type === g);
    if (!found) {
      return `
        <div class="panel" style="padding:14px">
          <h4 style="margin:0 0 6px 0;color:var(--violet)">${g}</h4>
          <p class="muted" style="margin:0 0 10px 0;font-size:12px">No measurement recorded</p>
          <button class="btn ghost btn-sm" type="button" onclick="switchCustomerTab('measurements');selectGarmentTab('${g}');openMeasurementForm('${g}')">＋ Record</button>
        </div>`;
    }
    const d = new Date(found.created_at).toLocaleDateString('en-IN', {month:'short', day:'numeric'});
    return `
      <div class="panel" style="padding:14px;border-color:var(--pink)">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
          <h4 style="margin:0;color:var(--violet)">${g}</h4>
          <span class="pill">${d}</span>
        </div>
        <p class="muted" style="margin:0 0 10px 0;font-size:12px">Fit: <strong>${escapeHTML(found.fit_preference || 'Regular')}</strong> (${found.unit})</p>
        <button class="btn light btn-sm" type="button" onclick="switchCustomerTab('measurements');selectGarmentTab('${g}')">View Snapshot</button>
      </div>`;
  }).join('');
}

function renderMeasurementsTimeline() {
  const tbody = document.getElementById('measureHistoryRows');
  if (!customerMeasurements.length) {
    tbody.innerHTML = '<tr><td colspan="6">No measurement snapshots saved yet.</td></tr>';
    return;
  }
  tbody.innerHTML = customerMeasurements.map(m => {
    const d = new Date(m.created_at).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
    let dims = {};
    try { dims = typeof m.dimensions === 'string' ? JSON.parse(m.dimensions) : (m.dimensions || {}); } catch(_) {}
    const preview = Object.entries(dims).slice(0, 4).map(([k,v]) => `${labels[k]||k}: ${v}`).join(', ') + (Object.keys(dims).length > 4 ? '…' : '');

    return `
      <tr>
        <td><strong>${escapeHTML(m.garment_type)}</strong></td>
        <td>${escapeHTML(m.unit)}</td>
        <td>${d}</td>
        <td>${escapeHTML(m.fit_preference || 'Regular')}</td>
        <td><small class="muted">${escapeHTML(preview)}</small></td>
        <td>
          <div style="display:flex;gap:6px">
            <button class="btn ghost btn-sm" type="button" onclick="copyAndEditMeasurement('${m.id}')">Copy & Edit</button>
            <button class="btn light btn-sm" type="button" onclick="useSnapshotForNewOrder('${m.id}')">Use in Order</button>
          </div>
        </td>
      </tr>`;
  }).join('');
}

function openMeasurementForm(garment = 'Blouse', existingDimensions = {}, fit = 'Regular', notes = '') {
  document.getElementById('measureGarmentSelect').value = garment;
  document.getElementById('measureFitPref').value = fit || 'Regular';
  document.getElementById('measureSpecialNotes').value = notes || '';
  drawDynamicMeasurementFields(garment, existingDimensions);

  document.getElementById('measureFormTitle').textContent = `Record ${garment} Measurement Snapshot`;
  document.getElementById('measureFormCard').classList.remove('hidden');
  document.getElementById('measureFormCard').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeMeasurementForm() {
  document.getElementById('measureFormCard').classList.add('hidden');
  document.getElementById('recordMeasurementForm').reset();
  document.getElementById('measureNotice').hidden = true;
}

function copyAndEditMeasurement(snapshotId) {
  const snap = customerMeasurements.find(m => m.id === snapshotId);
  if (!snap) return;
  let dims = {};
  try { dims = typeof snap.dimensions === 'string' ? JSON.parse(snap.dimensions) : (snap.dimensions || {}); } catch(_) {}
  openMeasurementForm(snap.garment_type, dims, snap.fit_preference, snap.notes);
  setNotice('#measureNotice', `Loaded values from snapshot (${new Date(snap.created_at).toLocaleDateString('en-IN')}). Modify as needed and save.`, true);
}

function drawDynamicMeasurementFields(garment, existingValues = {}) {
  const keys = measurementDefinitions[garment] || measurementDefinitions['General body measurements'];
  const unit = val('measureUnitSelect') === 'in' ? 'Inches' : 'cm';
  const container = document.getElementById('dynamicMeasureFields');

  container.innerHTML = keys.map(k => `
    <div class="field">
      <label for="dim_${k}">${escapeHTML(labels[k] || k)}</label>
      <input id="dim_${k}" data-measure-key="${k}" type="text" inputmode="decimal"
             placeholder="${unit}" value="${existingValues[k] ? escapeHTML(existingValues[k]) : ''}">
    </div>
  `).join('');
}

async function handleSaveMeasurement(e) {
  e.preventDefault();
  if (!currentCustomer) return;
  const garment = val('measureGarmentSelect');
  const unit = val('measureUnitSelect');
  const fitPreference = val('measureFitPref');
  const notes = val('measureSpecialNotes');

  const dimensions = {};
  document.querySelectorAll('[data-measure-key]').forEach(inp => {
    const v = inp.value.trim();
    if (v) dimensions[inp.dataset.measureKey] = v;
  });

  if (!Object.keys(dimensions).length) {
    setNotice('#measureNotice', 'Please fill in at least one measurement dimension.');
    return;
  }

  try {
    const saved = await API.post(`/api/tailor/customers/${currentCustomer.id}/measurements`, {
      garmentType: garment,
      unit,
      dimensions,
      fitPreference,
      notes
    });
    closeMeasurementForm();
    await loadCustomerMeasurements(currentCustomer.id);
    selectGarmentTab(garment);
    setNotice('#workspaceNotice', `Measurement snapshot for ${garment} saved successfully in database.`, true);
  } catch (err) {
    setNotice('#measureNotice', err.message);
  }
}

// ============================================================================
// ORDERS & BILLING: Step C Workflow
// ============================================================================
async function loadCustomerOrders(customerId) {
  try {
    customerOrders = await API.get('/api/tailor/customers/' + customerId + '/orders');
    document.getElementById('ordersCountBadge').textContent = customerOrders.length;
    document.getElementById('metricTotalOrders').textContent = customerOrders.length;

    const active = customerOrders.filter(o => ['NEW', 'IN_PROGRESS', 'TRIAL', 'READY'].includes(o.status));
    document.getElementById('metricActiveOrders').textContent = active.length;

    const dueTotal = customerOrders.reduce((sum, o) => sum + Math.max(0, Number(o.due || 0)), 0);
    document.getElementById('metricOutstandingDue').textContent = money(dueTotal);

    renderCustomerOrdersTable();
    renderActiveOrdersOverview(active);
    populatePaymentOrderDropdown();
  } catch (err) {
    console.error('Failed to load customer orders:', err);
  }
}

function renderActiveOrdersOverview(active) {
  const container = document.getElementById('activeOrdersList');
  if (!active.length) {
    container.innerHTML = `
      <div style="background:#fff;border:1px dashed #ebd3e8;border-radius:12px;padding:20px;text-align:center">
        <p class="muted" style="margin:0 0 10px 0">No active orders pending right now for ${escapeHTML(currentCustomer.name)}.</p>
        <button class="btn orange btn-sm" type="button" onclick="startNewOrder()">＋ Create New Order</button>
      </div>`;
    return;
  }

  container.innerHTML = active.map(o => `
    <div class="panel" style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px">
      <div>
        <div style="display:flex;align-items:center;gap:8px">
          <strong>${escapeHTML(o.order_number)}</strong>
          <span class="status-badge status-${o.status}">${ORDER_STATUS_LABELS[o.status] || o.status}</span>
          <span class="status-badge pay-${o.payment_status}">${formatPayBadge(o.payment_status)}</span>
        </div>
        <div style="font-size:13px;color:var(--muted);margin-top:4px">
          Promised Due Date: <strong>${o.due_date ? escapeHTML(o.due_date) : 'Not specified'}</strong> ·
          Total: <strong>${money(o.total)}</strong> ·
          Due: <strong style="color:#b30000">${money(o.due)}</strong>
        </div>
        ${o.notes ? `<div style="font-size:12px;color:var(--violet);margin-top:3px">Notes: ${escapeHTML(o.notes)}</div>` : ''}
      </div>
      <div style="display:flex;gap:6px;flex-wrap:wrap">
        <button class="btn light btn-sm" type="button" onclick="openEditOrder('${o.id}')">✏️ Edit Order</button>
        <button class="btn ghost btn-sm" type="button" onclick="viewOrderBill('${o.id}')">Invoice</button>
        <button class="btn ghost btn-sm" type="button" onclick="shareWhatsAppOrder('${o.id}')">WhatsApp</button>
        ${Number(o.due) > 0 ? `<button class="btn orange btn-sm" type="button" onclick="switchCustomerTab('payments');selectPaymentOrder('${o.id}')">💳 Collect Due</button>` : ''}
      </div>
    </div>
  `).join('');
}

function renderCustomerOrdersTable() {
  const tbody = document.getElementById('customerOrdersTableBody');
  if (!customerOrders.length) {
    tbody.innerHTML = '<tr><td colspan="9">No orders recorded for this customer yet.</td></tr>';
    return;
  }

  tbody.innerHTML = customerOrders.map(o => `
    <tr>
      <td><strong>${escapeHTML(o.order_number)}</strong></td>
      <td>${new Date(o.created_at).toLocaleDateString('en-IN', {month:'short', day:'numeric'})}</td>
      <td>
        <select onchange="updateOrderStatus('${o.id}', this.value)" style="padding:4px 8px;font-size:12px;border-radius:6px">
          ${Object.entries(ORDER_STATUS_LABELS).map(([k, label]) => `
            <option value="${k}" ${k === o.status ? 'selected' : ''}>${label}</option>
          `).join('')}
        </select>
      </td>
      <td><span class="status-badge pay-${o.payment_status}">${formatPayBadge(o.payment_status)}</span></td>
      <td>${money(o.total)}</td>
      <td>${money(o.amount_paid)}</td>
      <td><strong>${money(o.due)}</strong></td>
      <td>${o.due_date ? escapeHTML(o.due_date) : '—'}</td>
      <td>
        <div style="display:flex;gap:4px">
          <button class="btn ghost btn-sm" type="button" onclick="openEditOrder('${o.id}')">Edit</button>
          <button class="btn ghost btn-sm" type="button" onclick="viewOrderBill('${o.id}')">Bill</button>
          <button class="btn ghost btn-sm" type="button" onclick="shareWhatsAppOrder('${o.id}')">WA</button>
        </div>
      </td>
    </tr>
  `).join('');
}

function populateOrderMeasurementDropdown(selectedId = null) {
  const sel = document.getElementById('orderMeasurementSelect');
  if (!sel) return;

  sel.innerHTML = '<option value="">No measurement snapshot attached (Alteration / Ready-made)</option>' +
    customerMeasurements.map(m => {
      const d = new Date(m.created_at).toLocaleDateString('en-IN', {month:'short', day:'numeric'});
      return `<option value="${m.id}" ${m.id === selectedId ? 'selected' : ''}>${escapeHTML(m.garment_type)} — Snapshot (${d}) [${escapeHTML(m.fit_preference||'Regular')}]</option>`;
    }).join('');
}

function startNewOrder() {
  editingOrderId = null;
  cart = [];
  document.getElementById('orderEditorModeBadge').className = 'pill orange';
  document.getElementById('orderEditorModeBadge').textContent = 'Mode: Creating New Order';
  document.getElementById('orderEditorTitle').textContent = `New Stitching Order for ${currentCustomer.name}`;
  document.getElementById('saveOrderBtn').textContent = 'Confirm & Generate Unique Bill';
  document.getElementById('orderStatusFieldWrap').hidden = true;
  document.getElementById('advancePaymentSection').classList.remove('hidden');

  document.getElementById('orderDueDate').value = '';
  document.getElementById('orderDiscount').value = '0';
  document.getElementById('orderNotes').value = '';
  document.getElementById('orderAdvanceAmount').value = '0';
  document.getElementById('orderAdvanceRef').value = '';

  populateOrderMeasurementDropdown(customerMeasurements[0]?.id || null);
  drawCart();

  switchCustomerTab('orders');
  document.getElementById('orderEditorCard').classList.remove('hidden');
  document.getElementById('orderEditorCard').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function useSnapshotForNewOrder(snapshotId) {
  startNewOrder();
  populateOrderMeasurementDropdown(snapshotId);
  const snap = customerMeasurements.find(m => m.id === snapshotId);
  if (snap) {
    // Attempt to match product in dropdown
    const opt = Array.from(document.querySelectorAll('#orderProduct option')).find(o =>
      o.dataset.name?.toLowerCase().includes(snap.garment_type.toLowerCase())
    );
    if (opt) {
      document.getElementById('orderProduct').value = opt.value;
      updateRate();
    }
  }
}

async function openEditOrder(orderId) {
  try {
    const o = await API.get('/api/tailor/orders/' + orderId);
    editingOrderId = o.id;

    document.getElementById('orderEditorModeBadge').className = 'pill';
    document.getElementById('orderEditorModeBadge').textContent = `Mode: Editing Order ${o.order_number}`;
    document.getElementById('orderEditorTitle').textContent = `Update Order Instructions (${o.order_number})`;
    document.getElementById('saveOrderBtn').textContent = '💾 Save Changes to Order';

    // Populate fields
    document.getElementById('orderDueDate').value = o.due_date || '';
    document.getElementById('orderDiscount').value = Number(o.discount || 0);
    document.getElementById('orderNotes').value = o.notes || '';

    // Show status selector
    document.getElementById('orderStatusFieldWrap').hidden = false;
    document.getElementById('orderStatusSelect').value = o.status || 'NEW';

    // Hide advance payment (already in database)
    document.getElementById('advancePaymentSection').classList.add('hidden');

    // Populate measurement snapshot
    populateOrderMeasurementDropdown(o.measurement_id);

    // Populate cart items
    cart = (o.items || []).map(it => ({
      productId: it.product_id,
      name: it.item_name,
      quantity: Number(it.quantity || 1),
      defaultRate: Number(it.default_rate || 0),
      rate: Number(it.unit_rate || 0),
      unitRate: Number(it.unit_rate || 0),
      adjustmentReason: it.adjustment_reason || ''
    }));
    drawCart();

    switchCustomerTab('orders');
    document.getElementById('orderEditorCard').classList.remove('hidden');
    document.getElementById('orderEditorCard').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  } catch (err) {
    setNotice('#workspaceNotice', 'Failed to load order: ' + err.message);
  }
}

function cancelOrderEditing() {
  document.getElementById('orderEditorCard').classList.add('hidden');
  editingOrderId = null;
  cart = [];
}

// Products & Rates
async function loadProducts() {
  try {
    products = await API.get('/api/public/products');
    const sel = document.getElementById('orderProduct');
    sel.innerHTML = products.map(p =>
      `<option value="${p.id}" data-name="${escapeHTML(p.name)}" data-rate="${p.default_rate}">
        ${escapeHTML(p.name)} · ${money(p.default_rate)} ${p.product_type === 'READY_MADE' ? '(Ready-Made)' : ''}
      </option>`
    ).join('');
    updateRate();
  } catch (err) {
    console.error('Failed to load products:', err);
  }
}

function updateRate() {
  const opt = document.querySelector('#orderProduct option:checked');
  if (opt) {
    const rate = opt.dataset.rate || 0;
    document.getElementById('orderRate').value = rate;
    const badge = document.getElementById('defaultRateBadge');
    if (badge) badge.textContent = `Default: ${money(rate)}`;
    document.getElementById('orderReason').value = '';
  }
}

function addCartItem() {
  const p = products.find(p => p.id === val('orderProduct'));
  if (!p) {
    setNotice('#orderEditorNotice', 'Please select a tailoring service.');
    return;
  }
  const qty = Number(val('orderQty'));
  const rate = Number(val('orderRate'));
  if (!Number.isInteger(qty) || qty < 1 || rate < 0 || !Number.isFinite(rate)) {
    setNotice('#orderEditorNotice', 'Please specify a valid quantity and rate.');
    return;
  }
  const defaultRate = Number(p.default_rate || 0);
  const reason = val('orderReason');
  cart.push({
    productId: p.id,
    name: p.name,
    quantity: qty,
    defaultRate,
    rate,
    unitRate: rate,
    adjustmentReason: (rate !== defaultRate ? (reason || 'Price adjusted by tailor') : '')
  });
  document.getElementById('orderReason').value = '';
  drawCart();
}

function removeCartItem(idx) {
  cart.splice(idx, 1);
  drawCart();
}

function drawCart() {
  const tbody = document.getElementById('cartTableBody');
  tbody.innerHTML = cart.length ? cart.map((x, i) => {
    const isAdjusted = Number(x.rate) !== Number(x.defaultRate);
    return `
      <tr>
        <td>
          <strong>${escapeHTML(x.name)}</strong>
          ${isAdjusted ? `<br><small class="pill" style="font-size:10px;background:#fff2d6;color:#995400">Overridden: ${escapeHTML(x.adjustmentReason||'Custom rate')}</small>` : ''}
        </td>
        <td>${money(x.defaultRate)}</td>
        <td>${money(x.rate)}</td>
        <td>${x.quantity}</td>
        <td><strong>${money(x.quantity * x.rate)}</strong></td>
        <td><button class="btn ghost btn-sm" type="button" onclick="removeCartItem(${i})">✕</button></td>
      </tr>`;
  }).join('') : '<tr><td colspan="6">No services added to bill yet.</td></tr>';

  const subtotal = cart.reduce((sum, it) => sum + (it.quantity * it.rate), 0);
  const discount = Number(val('orderDiscount') || 0);
  const net = Math.max(0, subtotal - discount);

  document.getElementById('cartSubtotalText').textContent = money(subtotal);
  document.getElementById('cartTotalText').textContent = money(net);
}

async function handleSaveOrder() {
  if (!currentCustomer) {
    setNotice('#orderEditorNotice', 'Select a customer first.');
    return;
  }

  // If Editing an Existing Order
  if (editingOrderId) {
    const dueDate = val('orderDueDate');
    const notes = val('orderNotes');
    const measurementId = val('orderMeasurementSelect') || null;
    const status = val('orderStatusSelect');

    try {
      const updated = await API.patch('/api/tailor/orders/' + editingOrderId, {
        dueDate,
        notes,
        measurementId,
        status
      });
      cancelOrderEditing();
      await Promise.all([
        loadCustomerOrders(currentCustomer.id),
        loadRecentShopOrders()
      ]);
      setNotice('#workspaceNotice', `Order ${updated.order_number} updated successfully in database.`, true);
      viewOrderBill(updated.id);
    } catch (err) {
      setNotice('#orderEditorNotice', err.message);
    }
    return;
  }

  // If Creating a New Order
  if (!cart.length) {
    setNotice('#orderEditorNotice', 'Please add at least one tailoring service to the bill.');
    return;
  }

  const payload = {
    customerId: currentCustomer.id,
    measurementId: val('orderMeasurementSelect') || null,
    dueDate: val('orderDueDate') || null,
    notes: val('orderNotes') || null,
    discount: Number(val('orderDiscount') || 0),
    advanceAmount: Number(val('orderAdvanceAmount') || 0),
    advanceMethod: val('orderAdvanceMethod'),
    advanceRef: val('orderAdvanceRef') || null,
    items: cart
  };

  try {
    const created = await API.post('/api/tailor/orders', payload);
    cancelOrderEditing();
    await Promise.all([
      loadCustomerOrders(currentCustomer.id),
      loadRecentShopOrders()
    ]);
    setNotice('#workspaceNotice', `Order ${created.order_number} generated successfully in database.`, true);
    viewOrderBill(created.id);
  } catch (err) {
    setNotice('#orderEditorNotice', err.message);
  }
}

async function updateOrderStatus(orderId, newStatus) {
  try {
    await API.patch('/api/tailor/orders/' + orderId + '/status', { status: newStatus });
    if (currentCustomer) await loadCustomerOrders(currentCustomer.id);
    await loadRecentShopOrders();
    setNotice('#workspaceNotice', `Order status updated to ${ORDER_STATUS_LABELS[newStatus]||newStatus}.`, true);
  } catch (err) {
    alert('Failed to update status: ' + err.message);
  }
}

// Bill Invoice View & WhatsApp Delivery
async function viewOrderBill(orderId) {
  try {
    const o = await API.get('/api/tailor/orders/' + orderId);
    const card = document.getElementById('orderBillResultCard');
    card.classList.remove('hidden');

    const d = new Date(o.created_at).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
    const dueD = o.due_date ? new Date(o.due_date).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' }) : 'As agreed';

    card.innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;gap:10px;margin-bottom:14px">
        <div>
          <div class="eyebrow" style="color:var(--orange)">Bill & Invoice Preview</div>
          <h2 style="margin:2px 0;font-family:Georgia,serif;color:var(--violet)">${escapeHTML(o.order_number)}</h2>
          <small class="muted">Customer: <strong>${escapeHTML(o.customer)}</strong> (${escapeHTML(o.phone)}) · Order Date: ${d}</small>
        </div>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn ghost btn-sm" type="button" onclick="document.getElementById('orderBillResultCard').classList.add('hidden')">✕ Close</button>
          <button class="btn light btn-sm" type="button" onclick="shareWhatsAppOrder('${o.id}')">💬 WhatsApp e-Bill</button>
          <button class="btn orange btn-sm" type="button" onclick="printOrderInvoice('${o.id}')">🖨️ Print Invoice</button>
        </div>
      </div>

      <div style="background:#fdf9fd;border-radius:12px;padding:14px;border:1px solid #ebd3e8;margin-bottom:14px">
        <div style="display:flex;justify-content:space-between;font-size:13px;flex-wrap:wrap;gap:8px">
          <span>Status: <strong class="status-badge status-${o.status}">${ORDER_STATUS_LABELS[o.status]||o.status}</strong></span>
          <span>Payment: <strong class="status-badge pay-${o.payment_status}">${formatPayBadge(o.payment_status)}</strong></span>
          <span>Delivery Due: <strong>${dueD}</strong></span>
        </div>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Item / Service</th>
              <th>Rate</th>
              <th>Qty</th>
              <th>Amount</th>
            </tr>
          </thead>
          <tbody>
            ${(o.items || []).map(it => `
              <tr>
                <td>
                  <strong>${escapeHTML(it.item_name)}</strong>
                  ${it.adjustment_reason ? `<br><small class="muted">Reason: ${escapeHTML(it.adjustment_reason)}</small>` : ''}
                </td>
                <td>${money(it.unit_rate)}</td>
                <td>${it.quantity}</td>
                <td>${money(it.amount)}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
      </div>

      <div style="display:flex;justify-content:flex-end;margin-top:14px">
        <div style="width:260px;font-size:14px;line-height:1.8">
          <div style="display:flex;justify-content:space-between"><span>Subtotal:</span><span>${money(o.subtotal)}</span></div>
          <div style="display:flex;justify-content:space-between"><span>Discount:</span><span>-${money(o.discount)}</span></div>
          <div style="display:flex;justify-content:space-between;font-size:16px;font-weight:700;color:var(--violet);border-top:1.5px solid var(--violet);padding-top:4px">
            <span>Net Total:</span><span>${money(o.total)}</span>
          </div>
          <div style="display:flex;justify-content:space-between;color:var(--green)"><span>Paid:</span><span>${money(o.amount_paid)}</span></div>
          <div style="display:flex;justify-content:space-between;font-weight:700;color:#b30000;border-top:1px dashed #ccc;padding-top:4px">
            <span>Balance Due:</span><span>${money(o.due)}</span>
          </div>
        </div>
      </div>
    `;
    card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  } catch (err) {
    alert('Failed to preview bill: ' + err.message);
  }
}

async function shareWhatsAppOrder(orderId) {
  try {
    const o = await API.get('/api/tailor/orders/' + orderId);
    const phone = whatsappDigits(o.phone);
    if (!phone) {
      alert('Valid customer phone number is required for WhatsApp delivery.');
      return;
    }
    const billUrl = `${location.origin}/bill.html?id=${o.id}`;
    const text = `Namaste ${o.customer}! Your stitching bill ${o.order_number} from Jolly Ladies Tailor has been generated.\n\nTotal: ${money(o.total)}\nPaid: ${money(o.amount_paid)}\nBalance Due: ${money(o.due)}\n\nView and download your digital e-bill here:\n${billUrl}\n\nThank you for choosing Jolly Ladies Tailor!`;
    window.open(`https://wa.me/${phone}?text=${encodeURIComponent(text)}`, '_blank');
  } catch (err) {
    alert('WhatsApp sharing failed: ' + err.message);
  }
}

async function printOrderInvoice(orderId) {
  try {
    const o = await API.get('/api/tailor/orders/' + orderId);
    const w = window.open('', '_blank');
    w.document.write(`
      <!doctype html>
      <html>
      <head>
        <title>Invoice - ${escapeHTML(o.order_number)}</title>
        <style>
          body { font-family: Inter, Arial, sans-serif; padding: 24px; color: #333; }
          .header { text-align: center; border-bottom: 2px solid #400849; padding-bottom: 12px; margin-bottom: 20px; }
          .header h1 { margin: 0; color: #400849; }
          table { width: 100%; border-collapse: collapse; margin: 16px 0; }
          th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
          th { background: #fdf2f8; color: #400849; }
          .totals { width: 280px; margin-left: auto; font-size: 14px; line-height: 1.8; }
          @media print { body { padding: 0; } }
        </style>
      </head>
      <body>
        <div class="header">
          <h1>Jolly Ladies Tailor</h1>
          <p>Custom Ladies Tailoring & Alteration Specialist · Southern Avenue, Kolkata</p>
          <p><strong>Invoice / Bill: ${escapeHTML(o.order_number)}</strong></p>
        </div>
        <div>
          <p><strong>Customer:</strong> ${escapeHTML(o.customer)} (${escapeHTML(o.phone)})</p>
          <p><strong>Date:</strong> ${new Date(o.created_at).toLocaleDateString('en-IN')}</p>
        </div>
        <table>
          <thead>
            <tr><th>Service / Item</th><th>Rate</th><th>Qty</th><th>Total</th></tr>
          </thead>
          <tbody>
            ${(o.items || []).map(it => `
              <tr>
                <td>${escapeHTML(it.item_name)}</td>
                <td>${money(it.unit_rate)}</td>
                <td>${it.quantity}</td>
                <td>${money(it.amount)}</td>
              </tr>
            `).join('')}
          </tbody>
        </table>
        <div class="totals">
          <div>Subtotal: <strong>${money(o.subtotal)}</strong></div>
          <div>Discount: <strong>-${money(o.discount)}</strong></div>
          <div style="font-size:16px;border-top:1px solid #333;margin-top:4px;padding-top:4px">Net Total: <strong>${money(o.total)}</strong></div>
          <div>Paid: <strong>${money(o.amount_paid)}</strong></div>
          <div style="color:#b30000;font-weight:700">Due Balance: ${money(o.due)}</div>
        </div>
        <p style="text-align:center;margin-top:30px;font-size:12px;color:#777">Thank you for stitching with Jolly Ladies Tailor!</p>
      </body>
      </html>
    `);
    w.document.close();
    w.focus();
    setTimeout(() => w.print(), 300);
  } catch (err) {
    alert('Print failed: ' + err.message);
  }
}

// ============================================================================
// PAYMENTS & IN-SHOP GPAY QR: Step E
// ============================================================================
async function loadPaymentQr() {
  try {
    paymentQrData = await API.get('/api/tailor/payment-qr');
    if (paymentQrData.shopName) {
      document.getElementById('tailorQrShopName').textContent = paymentQrData.shopName;
    }
    if (paymentQrData.upiId) {
      document.getElementById('tailorQrUpiId').textContent = 'UPI ID: ' + paymentQrData.upiId;
    } else {
      document.getElementById('tailorQrUpiId').textContent = 'UPI ID: Configured in Admin Console';
    }
    const img = document.getElementById('tailorGpayQrImg');
    if (paymentQrData.gpayQrUrl) {
      img.src = paymentQrData.gpayQrUrl;
      img.style.display = 'block';
    } else {
      img.style.display = 'none';
    }
  } catch (e) {
    console.warn('Could not load tailor payment QR', e);
  }
}

function populatePaymentOrderDropdown() {
  const sel = document.getElementById('paymentOrderSelect');
  if (!sel) return;

  const dueOrders = customerOrders.filter(o => Number(o.due) > 0);
  sel.innerHTML = dueOrders.length ? dueOrders.map(o => `
    <option value="${o.id}">${escapeHTML(o.order_number)} · Due: ${money(o.due)}</option>
  `).join('') : '<option value="">No orders with due balance</option>';

  if (dueOrders[0]) {
    selectPaymentOrder(dueOrders[0].id);
  } else {
    document.getElementById('paymentAmountInput').value = '';
    document.getElementById('tailorQrDueAmount').textContent = '';
  }

  renderCustomerPaymentLedger();
}

function selectPaymentOrder(orderId) {
  const sel = document.getElementById('paymentOrderSelect');
  sel.value = orderId;
  const o = customerOrders.find(x => x.id === orderId);
  if (o) {
    document.getElementById('paymentAmountInput').value = Number(o.due).toFixed(2);
    document.getElementById('tailorQrDueAmount').textContent = `Amount to Collect: ${money(o.due)} (${o.order_number})`;
  }
}

async function renderCustomerPaymentLedger() {
  const tbody = document.getElementById('customerPaymentsLedgerBody');
  if (!tbody || !currentCustomer) return;

  try {
    // Collect all payments across customer orders
    const allPayments = [];
    for (const ord of customerOrders) {
      if (ord.payments && ord.payments.length) {
        ord.payments.forEach(p => allPayments.push({...p, orderNumber: ord.order_number}));
      }
    }
    allPayments.sort((a,b) => new Date(b.received_at) - new Date(a.received_at));

    if (!allPayments.length) {
      tbody.innerHTML = '<tr><td colspan="6">No payment transactions recorded for this customer yet.</td></tr>';
      return;
    }

    tbody.innerHTML = allPayments.map(p => `
      <tr>
        <td>${new Date(p.received_at).toLocaleDateString('en-IN', {month:'short', day:'numeric', hour:'2-digit', minute:'2-digit'})}</td>
        <td><strong>${escapeHTML(p.orderNumber)}</strong></td>
        <td><span class="pill">${escapeHTML(p.payment_type || 'PAYMENT')}</span></td>
        <td>${escapeHTML(p.method)}</td>
        <td><strong style="color:var(--green)">${money(p.amount)}</strong></td>
        <td><small class="muted">${escapeHTML(p.reference || p.note || '—')}</small></td>
      </tr>
    `).join('');
  } catch (err) {
    console.error('Ledger rendering error:', err);
  }
}

async function handleRecordPayment(e) {
  e.preventDefault();
  const orderId = val('paymentOrderSelect');
  if (!orderId) {
    setNotice('#paymentNotice', 'Please select an order with an outstanding balance.');
    return;
  }
  const type = val('paymentTypeSelect');
  const amount = Number(val('paymentAmountInput'));
  const method = val('paymentMethodSelect');
  const reference = val('paymentRefInput');

  if (amount <= 0 || !Number.isFinite(amount)) {
    setNotice('#paymentNotice', 'Please enter a valid amount greater than zero.');
    return;
  }

  try {
    if (type === 'REFUND') {
      await API.post(`/api/tailor/orders/${orderId}/refunds`, {
        amount,
        reason: reference || 'Customer requested refund'
      });
      setNotice('#paymentNotice', `Refund of ${money(amount)} recorded successfully in database.`, true);
    } else {
      await API.post(`/api/tailor/orders/${orderId}/payments`, {
        amount,
        method,
        reference
      });
      setNotice('#paymentNotice', `Payment of ${money(amount)} recorded in database. Verify UPI arrival in GPay.`, true);
    }

    document.getElementById('paymentRefInput').value = '';
    await Promise.all([
      loadCustomerOrders(currentCustomer.id),
      loadRecentShopOrders()
    ]);
  } catch (err) {
    setNotice('#paymentNotice', err.message);
  }
}

// ============================================================================
// RECENT SHOP-WIDE ORDERS
// ============================================================================
async function loadRecentShopOrders() {
  try {
    shopOrders = await API.get('/api/tailor/orders');
    const tbody = document.getElementById('recentShopOrdersRows');
    if (!shopOrders.length) {
      tbody.innerHTML = '<tr><td colspan="9">No orders recorded in the shop yet.</td></tr>';
      return;
    }
    tbody.innerHTML = shopOrders.slice(0, 15).map(o => `
      <tr>
        <td><strong>${escapeHTML(o.order_number)}</strong></td>
        <td>
          <a href="#" onclick="selectCustomer('${o.customer_id}');return false;" style="font-weight:700;color:var(--violet)">
            ${escapeHTML(o.customer)}
          </a>
          <br><small class="muted">${escapeHTML(o.phone)}</small>
        </td>
        <td><span class="status-badge status-${o.status}">${ORDER_STATUS_LABELS[o.status]||o.status}</span></td>
        <td><span class="status-badge pay-${o.payment_status}">${formatPayBadge(o.payment_status)}</span></td>
        <td>${money(o.total)}</td>
        <td>${money(o.amount_paid)}</td>
        <td><strong>${money(o.due)}</strong></td>
        <td>${o.due_date ? escapeHTML(o.due_date) : '—'}</td>
        <td>
          <div style="display:flex;gap:4px">
            <button class="btn light btn-sm" type="button" onclick="selectCustomer('${o.customer_id}')">Open Hub</button>
            <button class="btn ghost btn-sm" type="button" onclick="viewOrderBill('${o.id}')">Invoice</button>
          </div>
        </td>
      </tr>
    `).join('');
  } catch (err) {
    console.error('Failed to load shop orders:', err);
  }
}

// Helpers
function formatPayBadge(status) {
  if (status === 'PAID') return 'Fully Paid';
  if (status === 'PARTIALLY_PAID') return 'Partially Paid';
  if (status === 'REFUNDED') return 'Refunded';
  return 'Payment Due';
}

// ============================================================================
// Event Bindings
// ============================================================================
document.addEventListener('DOMContentLoaded', () => {
  // Auth
  document.getElementById('loginForm').addEventListener('submit', doLogin);
  document.getElementById('logout').addEventListener('click', logout);

  // Workflow Navigation
  document.getElementById('stepNav1').addEventListener('click', switchBackToCustomerSearch);
  document.getElementById('stepNav2').addEventListener('click', () => {
    if (currentCustomer) switchCustomerTab('overview');
  });
  document.getElementById('stepNav3').addEventListener('click', () => {
    if (currentCustomer) switchCustomerTab('orders');
  });

  // Step 1: Customer Search
  const searchInput = document.getElementById('customerSearchInput');
  searchInput.addEventListener('input', e => {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = setTimeout(() => searchCustomers(e.target.value.trim()), 250);
  });
  document.getElementById('searchBtn').addEventListener('click', () => searchCustomers(val('customerSearchInput')));

  // Step 1: Add New Customer
  document.getElementById('showNewCustomerBtn').addEventListener('click', () => openNewCustomerForm());
  document.getElementById('cancelNewCustomerBtn').addEventListener('click', closeNewCustomerForm);
  document.getElementById('cancelNewCustomerBtn2').addEventListener('click', closeNewCustomerForm);
  document.getElementById('newCustomerForm').addEventListener('submit', handleRegisterCustomer);
  document.getElementById('newCustPhone').addEventListener('input', e => checkDuplicatePhone(e.target.value.trim()));

  // Step 2: Customer Hub
  document.getElementById('hubEditCustomerBtn').addEventListener('click', openEditCustomerBox);
  document.getElementById('cancelEditCustomerBtn').addEventListener('click', closeEditCustomerBox);
  document.getElementById('cancelEditCustomerBtn2').addEventListener('click', closeEditCustomerBox);
  document.getElementById('editCustomerForm').addEventListener('submit', handleSaveCustomerEdits);

  document.getElementById('hubNewOrderBtn').addEventListener('click', startNewOrder);
  document.getElementById('overviewStartOrderBtn').addEventListener('click', startNewOrder);
  document.getElementById('tabOrdersNewOrderBtn').addEventListener('click', startNewOrder);
  document.getElementById('hubSwitchCustomerBtn').addEventListener('click', switchBackToCustomerSearch);
  document.getElementById('switchCustomerTopBtn').addEventListener('click', switchBackToCustomerSearch);

  // Tabs
  document.getElementById('tabBtnOverview').addEventListener('click', () => switchCustomerTab('overview'));
  document.getElementById('tabBtnMeasurements').addEventListener('click', () => switchCustomerTab('measurements'));
  document.getElementById('tabBtnOrders').addEventListener('click', () => switchCustomerTab('orders'));
  document.getElementById('tabBtnPayments').addEventListener('click', () => switchCustomerTab('payments'));

  // Garment selector buttons in measurements
  document.querySelectorAll('.garment-tab-btn').forEach(btn => {
    btn.addEventListener('click', () => selectGarmentTab(btn.dataset.garment));
  });

  // Measurement Form
  document.getElementById('takeFreshMeasureBtn').addEventListener('click', () => openMeasurementForm(selectedGarment));
  document.getElementById('closeMeasureFormBtn').addEventListener('click', closeMeasurementForm);
  document.getElementById('cancelMeasureFormBtn').addEventListener('click', closeMeasurementForm);
  document.getElementById('measureGarmentSelect').addEventListener('change', e => {
    drawDynamicMeasurementFields(e.target.value);
  });
  document.getElementById('measureUnitSelect').addEventListener('change', () => {
    drawDynamicMeasurementFields(val('measureGarmentSelect'));
  });
  document.getElementById('recordMeasurementForm').addEventListener('submit', handleSaveMeasurement);

  // Order Editor
  document.getElementById('orderProduct').addEventListener('change', updateRate);
  document.getElementById('addOrderItemBtn').addEventListener('click', addCartItem);
  document.getElementById('orderDiscount').addEventListener('input', drawCart);
  document.getElementById('saveOrderBtn').addEventListener('click', handleSaveOrder);
  document.getElementById('cancelOrderEditorBtn').addEventListener('click', cancelOrderEditing);
  document.getElementById('cancelOrderBtn2').addEventListener('click', cancelOrderEditing);

  // Payments & QR
  document.getElementById('toggleGpayQrBtn').addEventListener('click', () => {
    document.getElementById('shopGpayQrDisplay').classList.toggle('hidden');
  });
  document.getElementById('paymentOrderSelect').addEventListener('change', e => {
    selectPaymentOrder(e.target.value);
  });
  document.getElementById('recordPaymentForm').addEventListener('submit', handleRecordPayment);

  // Recent Shop Orders
  document.getElementById('refreshRecentShopOrdersBtn').addEventListener('click', loadRecentShopOrders);

  // Auto-session check on page load
  (async () => {
    try {
      const me = await API.get('/api/auth/me');
      if (['TAILOR', 'ADMIN'].includes(me.role)) {
        document.getElementById('whoami').textContent = `${me.username} · ${me.role}`;
        document.getElementById('loginPanel').classList.add('hidden');
        document.getElementById('workspace').classList.remove('hidden');
        document.getElementById('logout').classList.remove('hidden');
        await loadInitialWorkspace();
      }
    } catch (_) {
      /* Anonymous visitor */
    }
  })();
});
