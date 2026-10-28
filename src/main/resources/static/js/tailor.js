// ============================================================================
// Jolly Ladies Tailor — Tailor Workspace (5-Stage Sequential Workflow)
// 1. Customer -> 2. Garments & Order -> 3. Bill -> 4. Payment -> 5. WhatsApp
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
let currentStage = 1;
let currentCustomer = null;
let customerMeasurements = [];
let customerOrders = [];
let shopOrders = [];
let products = [];
let cart = [];
let editingOrderId = null;
let activeOrder = null;
let selectedGarment = 'Blouse';
let selectedPaymentMethod = 'UPI';
let shopSettings = {};
let paymentQrData = {};
let searchDebounceTimer = null;
let currentIdempotencyKey = null;

const val = id => {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
};

// ============================================================================
// STAGE NAVIGATION (1: Customer -> 2: Order -> 3: Bill -> 4: Pay -> 5: Final)
// ============================================================================
function goToStage(stageNum) {
  if (stageNum > 1 && !currentCustomer) {
    setNotice('#workspaceNotice', 'Please select or register a customer first.');
    return;
  }
  if (stageNum > 2 && !activeOrder && !editingOrderId && !cart.length) {
    setNotice('#workspaceNotice', 'Please configure garment items and generate a bill first.');
    return;
  }

  currentStage = stageNum;

  // Update Stepper Nav
  for (let s = 1; s <= 5; s++) {
    const navEl = document.getElementById(`stepNav${s}`);
    if (!navEl) continue;
    navEl.classList.remove('active', 'completed');
    if (s === stageNum) {
      navEl.classList.add('active');
    } else if (s < stageNum) {
      navEl.classList.add('completed');
    }
  }

  // Toggle Stage Views
  for (let s = 1; s <= 5; s++) {
    const viewEl = document.getElementById(`stage${s}View`);
    if (viewEl) {
      if (s === stageNum) {
        viewEl.classList.remove('hidden');
      } else {
        viewEl.classList.add('hidden');
      }
    }
  }

  // Update Persistent Customer Header Bar visibility
  const custBar = document.getElementById('selectedCustomerBar');
  const switchTopBtn = document.getElementById('switchCustomerTopBtn');
  if (currentCustomer && stageNum > 1) {
    if (custBar) custBar.classList.remove('hidden');
    if (switchTopBtn) switchTopBtn.classList.remove('hidden');
  } else {
    if (stageNum === 1 && !currentCustomer) {
      if (custBar) custBar.classList.add('hidden');
      if (switchTopBtn) switchTopBtn.classList.add('hidden');
    }
  }

  // Stage-specific Initializers
  if (stageNum === 2) {
    renderExistingOpenOrdersList();
  } else if (stageNum === 3 && activeOrder) {
    renderAuthoritativeBill();
  } else if (stageNum === 4 && activeOrder) {
    setupPaymentStage();
  } else if (stageNum === 5 && activeOrder) {
    setupFinalizeStage();
  }

  window.scrollTo({ top: 0, behavior: 'smooth' });
}

// Drawer closer utilities
function closeMeasurementDrawer() {
  const drawer = document.getElementById('measureHubDrawer');
  if (drawer) drawer.classList.add('hidden');
  closeMeasurementForm();
}

function closeCustomerOrdersDrawer() {
  const drawer = document.getElementById('customerOrdersDrawer');
  if (drawer) drawer.classList.add('hidden');
}

function closeEditCustomerBox() {
  const box = document.getElementById('editCustomerBox');
  if (box) box.classList.add('hidden');
  const notice = document.getElementById('editCustNotice');
  if (notice) notice.hidden = true;
}

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
    await API.csrf();
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
// STAGE 1: Customer Search & Seamless Registration
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
  if (countBadge) countBadge.textContent = `${list.length} found`;
  if (!grid) return;

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
  if (!card) return;
  card.classList.remove('hidden');
  const isDigits = /^\d+$/.test(initialNameOrPhone.replace(/\D/g, ''));
  if (isDigits && initialNameOrPhone.length >= 7) {
    document.getElementById('newCustPhone').value = initialNameOrPhone;
    document.getElementById('newCustName').value = '';
    document.getElementById('newCustName').focus();
  } else {
    document.getElementById('newCustName').value = initialNameOrPhone;
    document.getElementById('newCustPhone').value = '';
    document.getElementById('newCustName').focus();
  }
  card.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeNewCustomerForm() {
  const card = document.getElementById('newCustomerCard');
  if (card) card.classList.add('hidden');
  const form = document.getElementById('newCustomerForm');
  if (form) form.reset();
  const notice = document.getElementById('newCustNotice');
  if (notice) notice.hidden = true;
  const dup = document.getElementById('duplicatePhoneWarning');
  if (dup) dup.hidden = true;
}

async function checkDuplicatePhone(rawPhone) {
  const digits = rawPhone.replace(/\D/g, '');
  const warn = document.getElementById('duplicatePhoneWarning');
  if (!warn) return;
  if (digits.length < 10) {
    warn.hidden = true;
    return;
  }
  try {
    const existing = await API.get('/api/tailor/customers/lookup?phone=' + encodeURIComponent(digits));
    if (existing && existing.id) {
      warn.innerHTML = `⚠️ A customer named <strong>${escapeHTML(existing.name)}</strong> already exists with this phone number.
        <button class="btn light btn-sm" type="button" style="margin-left:8px" onclick="selectCustomer('${existing.id}')">Select Existing Customer</button>`;
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

  const btn = document.getElementById('saveNewCustomerBtn');
  if (btn) {
    btn.disabled = true;
    btn.textContent = 'Saving...';
  }

  try {
    const created = await API.post('/api/tailor/customers', { name, phone, address, notes });
    closeNewCustomerForm();
    setNotice('#workspaceNotice', `Customer ${created.name} registered successfully. Ready for order.`, true);
    await selectCustomer(created.id);
  } catch (err) {
    setNotice('#newCustNotice', err.message);
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = 'Save & Continue to Order →';
    }
  }
}

async function selectCustomer(customerId) {
  try {
    const c = await API.get('/api/tailor/customers/' + customerId);
    currentCustomer = c;

    // Reset customer drawers
    closeMeasurementDrawer();
    closeCustomerOrdersDrawer();
    closeEditCustomerBox();

    // Populate Persistent Header
    document.getElementById('hubCustomerName').textContent = c.name;
    document.getElementById('hubCustomerPhone').textContent = c.phone;
    document.querySelectorAll('.customer-name-label').forEach(el => el.textContent = c.name);

    const wa = document.getElementById('hubCustomerWaLink');
    const waDigits = whatsappDigits(c.phone);
    if (waDigits) {
      wa.href = `https://wa.me/${waDigits}`;
      wa.classList.remove('hidden');
    } else {
      wa.classList.add('hidden');
    }

    const addrEl = document.getElementById('hubCustomerAddress');
    if (c.address) {
      addrEl.textContent = '📍 ' + c.address;
      addrEl.classList.remove('hidden');
    } else {
      addrEl.textContent = '';
      addrEl.classList.add('hidden');
    }

    const notesEl = document.getElementById('hubCustomerNotes');
    if (c.notes) {
      notesEl.textContent = 'Fitting Notes: ' + c.notes;
      notesEl.classList.remove('hidden');
    } else {
      notesEl.textContent = '';
      notesEl.classList.add('hidden');
    }

    // Load customer data in parallel
    await Promise.all([
      loadCustomerMeasurements(c.id),
      loadCustomerOrders(c.id)
    ]);

    // Show persistent header
    document.getElementById('selectedCustomerBar').classList.remove('hidden');
    document.getElementById('switchCustomerTopBtn').classList.remove('hidden');

    // Automatically transition to Stage 2 with a clean order form
    startNewOrder(false);
    goToStage(2);
  } catch (err) {
    setNotice('#workspaceNotice', 'Failed to load customer details: ' + err.message);
  }
}

// ============================================================================
// BUTTON 5: "Switch" — Switch Customer Back to Search
// ============================================================================
function switchBackToCustomerSearch() {
  if (cart.length > 0 && !confirm('You have items in your current order bill. Are you sure you want to switch customers and discard them?')) {
    return;
  }
  closeMeasurementDrawer();
  closeCustomerOrdersDrawer();
  closeEditCustomerBox();

  currentCustomer = null;
  activeOrder = null;
  editingOrderId = null;
  cart = [];
  customerMeasurements = [];
  customerOrders = [];

  document.getElementById('selectedCustomerBar').classList.add('hidden');
  document.getElementById('switchCustomerTopBtn').classList.add('hidden');
  document.getElementById('orderDueDate').value = '';
  document.getElementById('orderDiscount').value = '0';
  document.getElementById('orderNotes').value = '';
  drawCart();

  goToStage(1);
  const searchInp = document.getElementById('customerSearchInput');
  if (searchInp) {
    searchInp.value = '';
    searchInp.focus();
  }
  searchCustomers('');
}

function startFreshNextCustomer() {
  switchBackToCustomerSearch();
}

// ============================================================================
// BUTTON 4: "Edit" — Edit Customer Details
// ============================================================================
function openEditCustomerBox() {
  if (!currentCustomer) {
    setNotice('#workspaceNotice', 'Select a customer first to edit details.');
    return;
  }
  closeMeasurementDrawer();
  closeCustomerOrdersDrawer();

  document.getElementById('editCustName').value = currentCustomer.name || '';
  document.getElementById('editCustPhone').value = currentCustomer.phone || '';
  document.getElementById('editCustAddress').value = currentCustomer.address || '';
  document.getElementById('editCustNotes').value = currentCustomer.notes || '';
  document.getElementById('editCustNotice').hidden = true;

  const box = document.getElementById('editCustomerBox');
  box.classList.remove('hidden');
  box.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function handleSaveCustomerEdits(e) {
  e.preventDefault();
  if (!currentCustomer) return;
  const name = val('editCustName');
  const phone = val('editCustPhone');
  const address = val('editCustAddress');
  const notes = val('editCustNotes');

  if (!name || !phone) {
    setNotice('#editCustNotice', 'Name and mobile number are required.');
    return;
  }

  const saveBtn = document.querySelector('#editCustomerForm button[type="submit"]');
  if (saveBtn) {
    saveBtn.disabled = true;
    saveBtn.textContent = 'Saving...';
  }

  try {
    const updated = await API.patch('/api/tailor/customers/' + currentCustomer.id, {
      name,
      phone,
      address,
      notes
    });
    currentCustomer = updated;

    // Update persistent customer header in place
    document.getElementById('hubCustomerName').textContent = updated.name;
    document.getElementById('hubCustomerPhone').textContent = updated.phone;
    document.querySelectorAll('.customer-name-label').forEach(el => el.textContent = updated.name);

    const wa = document.getElementById('hubCustomerWaLink');
    const waDigits = whatsappDigits(updated.phone);
    if (waDigits) {
      wa.href = `https://wa.me/${waDigits}`;
      wa.classList.remove('hidden');
    } else {
      wa.classList.add('hidden');
    }

    const addrEl = document.getElementById('hubCustomerAddress');
    if (updated.address) {
      addrEl.textContent = '📍 ' + updated.address;
      addrEl.classList.remove('hidden');
    } else {
      addrEl.textContent = '';
      addrEl.classList.add('hidden');
    }

    const notesEl = document.getElementById('hubCustomerNotes');
    if (updated.notes) {
      notesEl.textContent = 'Fitting Notes: ' + updated.notes;
      notesEl.classList.remove('hidden');
    } else {
      notesEl.textContent = '';
      notesEl.classList.add('hidden');
    }

    closeEditCustomerBox();
    setNotice('#workspaceNotice', `Customer ${updated.name} updated successfully.`, true);

    // Refresh background list caches
    searchCustomers(val('customerSearchInput'));
    loadRecentShopOrders();
  } catch (err) {
    setNotice('#editCustNotice', err.message);
  } finally {
    if (saveBtn) {
      saveBtn.disabled = false;
      saveBtn.textContent = 'Save Changes';
    }
  }
}

// ============================================================================
// BUTTON 2: "Measurements (count)" — Hub, Snapshots & Inline Recording
// ============================================================================
function toggleMeasurementHub() {
  if (!currentCustomer) {
    setNotice('#workspaceNotice', 'Please select a customer first.');
    return;
  }
  closeCustomerOrdersDrawer();
  closeEditCustomerBox();

  const drawer = document.getElementById('measureHubDrawer');
  const isOpening = drawer.classList.contains('hidden');
  drawer.classList.toggle('hidden');

  if (isOpening) {
    loadCustomerMeasurements(currentCustomer.id).then(() => {
      renderGarmentSnapshot(selectedGarment);
      renderMeasurementsTimeline();
      drawer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  }
}

async function loadCustomerMeasurements(customerId) {
  try {
    customerMeasurements = await API.get('/api/tailor/customers/' + customerId + '/measurements');
    const badge = document.getElementById('measureCountBadge');
    if (badge) badge.textContent = customerMeasurements.length;

    const metricDate = document.getElementById('metricLatestMeasureDate');
    if (metricDate) {
      if (customerMeasurements.length) {
        const latest = customerMeasurements[0];
        const d = new Date(latest.created_at).toLocaleDateString('en-IN', { month: 'short', day: 'numeric' });
        metricDate.textContent = `${latest.garment_type} (${d})`;
      } else {
        metricDate.textContent = 'None saved';
      }
    }
    renderGarmentSnapshot(selectedGarment);
    renderMeasurementsTimeline();
    populateOrderMeasurementDropdown();
  } catch (err) {
    console.error('Failed to load customer measurements:', err);
  }
}

function selectGarmentTab(garment) {
  selectedGarment = garment;
  document.querySelectorAll('.garment-tab-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.garment === garment);
  });
  renderGarmentSnapshot(garment);
}

function renderGarmentSnapshot(garment) {
  const card = document.getElementById('garmentSnapshotDisplay');
  if (!card) return;

  const snaps = customerMeasurements.filter(m => m.garment_type === garment);
  if (!snaps.length) {
    card.innerHTML = `
      <div style="text-align:center;padding:16px">
        <p class="muted" style="margin:0 0 10px 0">No measurement snapshot recorded yet for <strong>${escapeHTML(garment)}</strong>.</p>
        <button class="btn light btn-sm" type="button" onclick="openMeasurementForm('${escapeHTML(garment)}')">＋ Record ${escapeHTML(garment)} Measurements</button>
      </div>`;
    return;
  }

  const latest = snaps[0];
  const d = new Date(latest.created_at).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
  let dims = {};
  try {
    dims = typeof latest.dimensions === 'string' ? JSON.parse(latest.dimensions) : (latest.dimensions || {});
  } catch (_) {}

  card.innerHTML = `
    <div class="snapshot-card-header">
      <div>
        <h4 style="margin:0;color:var(--violet)">${escapeHTML(latest.garment_type)} Snapshot</h4>
        <span class="muted" style="font-size:12px">Recorded on ${d} · Unit: <strong>${latest.unit}</strong> · Fit: <strong>${escapeHTML(latest.fit_preference || 'Regular')}</strong></span>
      </div>
      <div style="display:flex;gap:6px">
        <button class="btn ghost btn-sm" type="button" onclick="copyAndEditMeasurement('${latest.id}')">Copy & Edit</button>
        <button class="btn light btn-sm" type="button" onclick="useSnapshotForNewOrder('${latest.id}')">Use in Order</button>
      </div>
    </div>
    <div class="dimension-chips">
      ${Object.entries(dims).map(([k, v]) => `
        <div class="dim-chip">
          <span>${escapeHTML(labels[k] || k)}:</span>
          <strong>${escapeHTML(v)} ${latest.unit}</strong>
        </div>
      `).join('')}
    </div>
    ${latest.notes ? `<div style="font-size:12px;color:var(--violet);margin-top:10px;font-style:italic">Notes: ${escapeHTML(latest.notes)}</div>` : ''}
  `;
}

function renderMeasurementsTimeline() {
  const tbody = document.getElementById('measureHistoryRows');
  if (!tbody) return;
  if (!customerMeasurements.length) {
    tbody.innerHTML = '<tr><td colspan="6">No measurement snapshots saved yet for this customer.</td></tr>';
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
  const card = document.getElementById('measureFormCard');
  if (card) card.classList.add('hidden');
  const form = document.getElementById('recordMeasurementForm');
  if (form) form.reset();
  const notice = document.getElementById('measureNotice');
  if (notice) notice.hidden = true;
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
  if (!container) return;

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

  const saveBtn = document.querySelector('#recordMeasurementForm button[type="submit"]');
  if (saveBtn) {
    saveBtn.disabled = true;
    saveBtn.textContent = 'Saving...';
  }

  try {
    await API.post(`/api/tailor/customers/${currentCustomer.id}/measurements`, {
      garmentType: garment,
      unit,
      dimensions,
      fitPreference,
      notes
    });
    closeMeasurementForm();
    await loadCustomerMeasurements(currentCustomer.id);
    selectGarmentTab(garment);
    setNotice('#workspaceNotice', `Measurement snapshot for ${garment} saved successfully.`, true);
  } catch (err) {
    setNotice('#measureNotice', err.message);
  } finally {
    if (saveBtn) {
      saveBtn.disabled = false;
      saveBtn.textContent = '💾 Save Measurement Snapshot';
    }
  }
}

// ============================================================================
// BUTTON 3: "Orders (count)" — Customer Orders Drawer & Status Updates
// ============================================================================
function toggleCustomerOrdersList() {
  if (!currentCustomer) {
    setNotice('#workspaceNotice', 'Please select a customer first.');
    return;
  }
  closeMeasurementDrawer();
  closeEditCustomerBox();

  const drawer = document.getElementById('customerOrdersDrawer');
  const isOpening = drawer.classList.contains('hidden');
  drawer.classList.toggle('hidden');

  if (isOpening) {
    loadCustomerOrders(currentCustomer.id).then(() => {
      renderCustomerOrdersTable();
      drawer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  }
}

async function loadCustomerOrders(customerId) {
  try {
    customerOrders = await API.get('/api/tailor/customers/' + customerId + '/orders');
    const badge = document.getElementById('ordersCountBadge');
    if (badge) badge.textContent = customerOrders.length;
    const totalOrdersEl = document.getElementById('metricTotalOrders');
    if (totalOrdersEl) totalOrdersEl.textContent = customerOrders.length;

    const active = customerOrders.filter(o => ['NEW', 'IN_PROGRESS', 'TRIAL', 'READY'].includes(o.status));
    const activeOrdersEl = document.getElementById('metricActiveOrders');
    if (activeOrdersEl) activeOrdersEl.textContent = active.length;

    const dueTotal = customerOrders.reduce((sum, o) => sum + Math.max(0, Number(o.due || 0)), 0);
    const dueEl = document.getElementById('metricOutstandingDue');
    if (dueEl) dueEl.textContent = money(dueTotal);

    renderCustomerOrdersTable();
    renderExistingOpenOrdersList();
  } catch (err) {
    console.error('Failed to load customer orders:', err);
  }
}

function renderExistingOpenOrdersList() {
  const card = document.getElementById('existingOpenOrdersCard');
  const container = document.getElementById('existingOpenOrdersList');
  if (!card || !container) return;

  const openOrders = customerOrders.filter(o => ['NEW', 'IN_PROGRESS', 'TRIAL', 'READY'].includes(o.status));
  if (!openOrders.length) {
    card.classList.add('hidden');
    return;
  }

  card.classList.remove('hidden');
  container.innerHTML = openOrders.map(o => `
    <div style="background:#fff;border:1px solid #ebd3e8;border-radius:10px;padding:12px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px">
      <div>
        <div style="display:flex;align-items:center;gap:8px">
          <strong>${escapeHTML(o.order_number)}</strong>
          <span class="status-badge status-${o.status}">${ORDER_STATUS_LABELS[o.status] || o.status}</span>
          <span class="status-badge pay-${o.payment_status}">${formatPayBadge(o.payment_status)}</span>
        </div>
        <div class="muted" style="font-size:12px;margin-top:3px">
          Total: <strong>${money(o.total)}</strong> · Paid: <strong>${money(o.amount_paid)}</strong> · Due: <strong style="color:#b30000">${money(o.due)}</strong>
          ${o.due_date ? `· Promised Delivery: ${escapeHTML(o.due_date)}` : ''}
        </div>
      </div>
      <div style="display:flex;gap:6px">
        <button class="btn light btn-sm" type="button" onclick="openEditOrder('${o.id}')">✏️ Resume / Edit Order</button>
        <button class="btn ghost btn-sm" type="button" onclick="viewOrderAuthoritativeBill('${o.id}')">Review Bill</button>
      </div>
    </div>
  `).join('');
}

function renderCustomerOrdersTable() {
  const tbody = document.getElementById('customerOrdersTableBody');
  if (!tbody) return;
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
          <button class="btn light btn-sm" type="button" onclick="openEditOrder('${o.id}')">Edit</button>
          <button class="btn ghost btn-sm" type="button" onclick="viewOrderAuthoritativeBill('${o.id}')">Bill</button>
          <button class="btn ghost btn-sm" type="button" onclick="quickShareWhatsApp('${o.id}')">WA</button>
        </div>
      </td>
    </tr>
  `).join('');
}

// ============================================================================
// BUTTON 1: "New Order" — Start a clean new stitching order
// ============================================================================
function startNewOrder(promptIfDirty = true) {
  if (!currentCustomer) {
    setNotice('#workspaceNotice', 'Please select or register a customer first.');
    goToStage(1);
    return;
  }
  if (promptIfDirty && cart.length > 0 && !confirm('You have items in your current order bill. Start a new order and discard current items?')) {
    return;
  }

  // Close all open drawers
  closeMeasurementDrawer();
  closeCustomerOrdersDrawer();
  closeEditCustomerBox();

  editingOrderId = null;
  activeOrder = null;
  cart = [];

  const badge = document.getElementById('orderEditorModeBadge');
  if (badge) {
    badge.className = 'pill orange';
    badge.textContent = 'Mode: Creating New Order';
  }
  const title = document.getElementById('orderEditorTitle');
  if (title) title.textContent = `2. New Stitching Order for ${currentCustomer.name}`;
  const genBtn = document.getElementById('generateBillBtn');
  if (genBtn) genBtn.textContent = 'Generate & Review Bill →';
  const statusWrap = document.getElementById('orderStatusFieldWrap');
  if (statusWrap) statusWrap.hidden = true;

  document.getElementById('orderDueDate').value = '';
  document.getElementById('orderDiscount').value = '0';
  document.getElementById('orderNotes').value = '';
  document.getElementById('orderQty').value = '1';
  document.getElementById('orderReason').value = '';

  populateOrderMeasurementDropdown(customerMeasurements[0]?.id || null);
  updateRate();
  drawCart();

  goToStage(2);
  const stage2El = document.getElementById('stage2View');
  if (stage2El) stage2El.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function resetOrderForm() {
  if (confirm('Are you sure you want to clear current garment selections?')) {
    startNewOrder(false);
  }
}

function useSnapshotForNewOrder(snapshotId) {
  closeMeasurementDrawer();
  startNewOrder(false);
  populateOrderMeasurementDropdown(snapshotId);
  const snap = customerMeasurements.find(m => m.id === snapshotId);
  if (snap) {
    const opt = Array.from(document.querySelectorAll('#orderProduct option')).find(o =>
      o.dataset.name?.toLowerCase().includes(snap.garment_type.toLowerCase())
    );
    if (opt) {
      document.getElementById('orderProduct').value = opt.value;
      updateRate();
    }
  }
  goToStage(2);
}

async function openEditOrder(orderId) {
  try {
    closeCustomerOrdersDrawer();
    closeMeasurementDrawer();
    closeEditCustomerBox();

    const o = await API.get('/api/tailor/orders/' + orderId);
    editingOrderId = o.id;
    activeOrder = o;

    const badge = document.getElementById('orderEditorModeBadge');
    if (badge) {
      badge.className = 'pill';
      badge.textContent = `Mode: Editing Order ${o.order_number}`;
    }
    const title = document.getElementById('orderEditorTitle');
    if (title) title.textContent = `2. Update Order Instructions (${o.order_number})`;
    const genBtn = document.getElementById('generateBillBtn');
    if (genBtn) genBtn.textContent = 'Save Changes & Review Bill →';

    document.getElementById('orderDueDate').value = o.due_date || '';
    document.getElementById('orderDiscount').value = Number(o.discount || 0);
    document.getElementById('orderNotes').value = o.notes || '';

    const statusWrap = document.getElementById('orderStatusFieldWrap');
    if (statusWrap) {
      statusWrap.hidden = false;
      document.getElementById('orderStatusSelect').value = o.status || 'NEW';
    }

    populateOrderMeasurementDropdown(o.measurement_id);

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

    goToStage(2);
    const stage2El = document.getElementById('stage2View');
    if (stage2El) stage2El.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (err) {
    setNotice('#workspaceNotice', 'Failed to load order: ' + err.message);
  }
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

async function loadProducts() {
  try {
    products = await API.get('/api/public/products');
    const sel = document.getElementById('orderProduct');
    if (!sel) return;
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
    const rateInp = document.getElementById('orderRate');
    if (rateInp) rateInp.value = rate;
    const badge = document.getElementById('defaultRateBadge');
    if (badge) badge.textContent = `Default: ${money(rate)}`;
    const reasonInp = document.getElementById('orderReason');
    if (reasonInp) reasonInp.value = '';
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
    setNotice('#orderEditorNotice', 'Please specify a valid positive quantity and rate.');
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
  if (!tbody) return;
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
  document.getElementById('cartDiscountText').textContent = `-${money(discount)}`;
  document.getElementById('cartTotalText').textContent = money(net);
}

// Generate & Review Bill Action
async function handleGenerateBill() {
  if (!currentCustomer) {
    setNotice('#orderEditorNotice', 'Select a customer first.');
    return;
  }
  if (!cart.length) {
    setNotice('#orderEditorNotice', 'Please add at least one garment or service to the bill.');
    return;
  }

  const dueDate = val('orderDueDate') || null;
  const notes = val('orderNotes') || null;
  const measurementId = val('orderMeasurementSelect') || null;
  const discount = Number(val('orderDiscount') || 0);
  const genBtn = document.getElementById('generateBillBtn');

  if (genBtn) {
    genBtn.disabled = true;
    genBtn.textContent = 'Generating Bill...';
  }

  // If Editing Existing Order
  if (editingOrderId) {
    const status = val('orderStatusSelect') || 'NEW';
    try {
      const updated = await API.patch('/api/tailor/orders/' + editingOrderId, {
        items: cart,
        discount,
        dueDate,
        notes,
        measurementId,
        status
      });
      activeOrder = updated;
      await Promise.all([
        loadCustomerOrders(currentCustomer.id),
        loadRecentShopOrders()
      ]);
      setNotice('#workspaceNotice', `Order ${updated.order_number} saved. Review authoritative bill below.`, true);
      goToStage(3);
    } catch (err) {
      setNotice('#orderEditorNotice', err.message);
    } finally {
      if (genBtn) {
        genBtn.disabled = false;
        genBtn.textContent = 'Save Changes & Review Bill →';
      }
    }
    return;
  }

  // If Creating New Order
  try {
    const created = await API.post('/api/tailor/orders', {
      customerId: currentCustomer.id,
      measurementId,
      dueDate,
      notes,
      discount,
      items: cart
    });
    activeOrder = created;
    editingOrderId = created.id;
    await Promise.all([
      loadCustomerOrders(currentCustomer.id),
      loadRecentShopOrders()
    ]);
    setNotice('#workspaceNotice', `Order ${created.order_number} generated. Review authoritative bill below.`, true);
    goToStage(3);
  } catch (err) {
    setNotice('#orderEditorNotice', err.message);
  } finally {
    if (genBtn) {
      genBtn.disabled = false;
      genBtn.textContent = 'Generate & Review Bill →';
    }
  }
}

// ============================================================================
// STAGE 3: Authoritative Bill Review & Presentation
// ============================================================================
async function viewOrderAuthoritativeBill(orderId) {
  try {
    closeCustomerOrdersDrawer();
    closeMeasurementDrawer();
    activeOrder = await API.get('/api/tailor/orders/' + orderId);
    editingOrderId = activeOrder.id;
    goToStage(3);
  } catch (err) {
    setNotice('#workspaceNotice', 'Failed to load bill: ' + err.message);
  }
}

function renderAuthoritativeBill() {
  if (!activeOrder) return;
  const o = activeOrder;

  const d = new Date(o.created_at).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
  const dueD = o.due_date ? new Date(o.due_date).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' }) : 'As agreed';

  document.getElementById('billOrderNumber').textContent = o.order_number;
  document.getElementById('billOrderDate').textContent = `Date: ${d}`;
  document.getElementById('billCustomerName').textContent = o.customer;
  document.getElementById('billCustomerPhone').textContent = `📱 ${o.phone}`;
  document.getElementById('billCustomerAddress').textContent = o.address ? `📍 ${o.address}` : '';
  document.getElementById('billDueDate').textContent = dueD;

  const statusBadge = document.getElementById('billStatusBadge');
  statusBadge.className = `status-badge status-${o.status}`;
  statusBadge.textContent = ORDER_STATUS_LABELS[o.status] || o.status;

  const payBadge = document.getElementById('billPayStatusBadge');
  payBadge.className = `status-badge pay-${o.payment_status}`;
  payBadge.textContent = formatPayBadge(o.payment_status);

  // Items table
  const tbody = document.getElementById('billItemsTableBody');
  tbody.innerHTML = (o.items || []).map(it => `
    <tr>
      <td>
        <strong>${escapeHTML(it.item_name)}</strong>
        ${it.adjustment_reason ? `<br><small class="muted">Note: ${escapeHTML(it.adjustment_reason)}</small>` : ''}
      </td>
      <td>${money(it.unit_rate)}</td>
      <td>${it.quantity}</td>
      <td><strong>${money(it.amount)}</strong></td>
    </tr>
  `).join('');

  // Financials
  document.getElementById('billSubtotal').textContent = money(o.subtotal);
  document.getElementById('billDiscount').textContent = `-${money(o.discount)}`;
  document.getElementById('billTotal').textContent = money(o.total);
  document.getElementById('billPreviouslyPaid').textContent = money(o.amount_paid);
  document.getElementById('billOutstandingDue').textContent = money(o.due);

  // Action links
  const pdfBtn = document.getElementById('downloadPdfFromBillBtn');
  pdfBtn.href = `/api/tailor/orders/${o.id}/pdf`;
  pdfBtn.setAttribute('download', `invoice-${o.order_number}.pdf`);

  const proceedBtn = document.getElementById('proceedToPaymentBtn');
  if (Number(o.due) <= 0) {
    proceedBtn.textContent = 'Order Fully Paid → Finalize';
    proceedBtn.onclick = () => goToStage(5);
  } else {
    proceedBtn.textContent = `Choose Payment Method (${money(o.due)}) →`;
    proceedBtn.onclick = () => goToStage(4);
  }
}

// ============================================================================
// STAGE 4: Payment Method Selection & Real-Time Confirmation
// (Only Verified UPI / QR Code and Cash supported - Card option removed)
// ============================================================================
async function loadPaymentQr() {
  try {
    paymentQrData = await API.get('/api/tailor/payment-qr');
    const shopName = paymentQrData.shopName || 'Jolly Banerjee · Jolly Ladies Tailor';
    const qrNameEl = document.getElementById('tailorQrShopName');
    if (qrNameEl) qrNameEl.textContent = shopName;

    const upi = paymentQrData.upiId || 'jollybanerjee2021@oksbi';
    const upiEl = document.getElementById('upiIdText');
    if (upiEl) upiEl.textContent = upi;

    const img = document.getElementById('tailorGpayQrImg');
    if (img) {
      img.src = paymentQrData.gpayQrUrl || '/images/jolly-ladies-tailor-qr.jpg';
      img.style.display = 'block';
    }
  } catch (e) {
    console.warn('Could not load tailor payment QR', e);
  }
}

function setupPaymentStage() {
  if (!activeOrder) return;
  const o = activeOrder;
  const due = Math.max(0, Number(o.due || 0));

  document.getElementById('payGridTotal').textContent = money(o.total);
  document.getElementById('payGridPaid').textContent = money(o.amount_paid);
  document.getElementById('payGridPayingNow').textContent = money(due);
  document.getElementById('payGridDueAfter').textContent = money(0);

  document.getElementById('payNowInput').value = due.toFixed(2);
  document.getElementById('quickPayFullBtn').textContent = `Full Balance (${money(due)})`;
  document.getElementById('paymentRefInput').value = '';
  document.getElementById('paymentStageNotice').hidden = true;

  // Fresh idempotency key for this payment flow
  currentIdempotencyKey = 'PAY_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);

  selectPaymentMethod('UPI');
  updateUpiIntentLink(due);
}

function selectPaymentMethod(method) {
  selectedPaymentMethod = (method === 'CASH') ? 'CASH' : 'UPI';
  const upiChoice = document.getElementById('methodChoiceUpi');
  if (upiChoice) upiChoice.classList.toggle('active', selectedPaymentMethod === 'UPI');
  const cashChoice = document.getElementById('methodChoiceCash');
  if (cashChoice) cashChoice.classList.toggle('active', selectedPaymentMethod === 'CASH');

  const qrPanel = document.getElementById('upiQrPanel');
  if (qrPanel) {
    if (selectedPaymentMethod === 'UPI') {
      qrPanel.classList.remove('hidden');
      updateUpiIntentLink(Number(val('payNowInput') || 0));
    } else {
      qrPanel.classList.add('hidden');
    }
  }
}

function updateUpiIntentLink(amount) {
  if (!activeOrder) return;
  const upiId = paymentQrData.upiId || 'jollybanerjee2021@oksbi';
  const name = encodeURIComponent('Jolly Banerjee');
  const ref = encodeURIComponent(`Invoice-${activeOrder.order_number}`);
  const amt = Number(amount || 0).toFixed(2);
  const upiUrl = `upi://pay?pa=${upiId}&pn=${name}&am=${amt}&tn=${ref}&cu=INR`;
  const link = document.getElementById('tailorUpiIntentLink');
  if (link) link.href = upiUrl;
}

function setPayAmountFull() {
  if (!activeOrder) return;
  const due = Math.max(0, Number(activeOrder.due || 0));
  document.getElementById('payNowInput').value = due.toFixed(2);
  handlePayAmountChange();
}

function setPayAmountZero() {
  document.getElementById('payNowInput').value = '0.00';
  handlePayAmountChange();
}

function handlePayAmountChange() {
  if (!activeOrder) return;
  const total = Number(activeOrder.total || 0);
  const paidAlready = Number(activeOrder.amount_paid || 0);
  const due = Math.max(0, total - paidAlready);

  const payingNow = Math.max(0, Number(val('payNowInput') || 0));
  const dueAfter = Math.max(0, due - payingNow);

  document.getElementById('payGridPayingNow').textContent = money(payingNow);
  document.getElementById('payGridDueAfter').textContent = money(dueAfter);

  const notice = document.getElementById('paymentStageNotice');
  if (payingNow > due) {
    notice.className = 'notice error';
    notice.textContent = `Amount ₹${payingNow.toFixed(2)} exceeds total outstanding balance ₹${due.toFixed(2)}.`;
    notice.hidden = false;
  } else {
    notice.hidden = true;
  }

  if (selectedPaymentMethod === 'UPI') {
    updateUpiIntentLink(payingNow);
  }
}

function copyUpiId() {
  const upi = document.getElementById('upiIdText').textContent;
  navigator.clipboard.writeText(upi).then(() => {
    setNotice('#paymentStageNotice', `UPI ID "${upi}" copied to clipboard!`, true);
  }).catch(() => {
    alert('UPI ID: ' + upi);
  });
}

async function handleConfirmPayment() {
  if (!activeOrder) return;
  const amount = Number(val('payNowInput') || 0);
  const ref = val('paymentRefInput');
  const btn = document.getElementById('confirmPaymentBtn');

  if (amount <= 0) {
    skipPaymentToFinalize();
    return;
  }

  const due = Math.max(0, Number(activeOrder.due || 0));
  if (amount > due) {
    setNotice('#paymentStageNotice', `Payment amount ₹${amount.toFixed(2)} exceeds outstanding balance ₹${due.toFixed(2)}.`);
    return;
  }

  // Double-submit protection
  btn.disabled = true;
  btn.textContent = 'Recording Payment...';

  try {
    const res = await API.post(`/api/tailor/orders/${activeOrder.id}/payments`, {
      amount,
      method: selectedPaymentMethod,
      reference: ref,
      idempotencyKey: currentIdempotencyKey
    });

    if (res && res.order) {
      activeOrder = res.order;
    } else {
      activeOrder = await API.get('/api/tailor/orders/' + activeOrder.id);
    }

    await Promise.all([
      loadCustomerOrders(currentCustomer.id),
      loadRecentShopOrders()
    ]);

    setNotice('#workspaceNotice', `Payment of ${money(amount)} confirmed in database.`, true);
    goToStage(5);
  } catch (err) {
    setNotice('#paymentStageNotice', 'Payment failed: ' + err.message);
  } finally {
    btn.disabled = false;
    btn.textContent = 'Confirm Payment & Finalize →';
  }
}

function skipPaymentToFinalize() {
  goToStage(5);
}

// ============================================================================
// STAGE 5: Finalized Invoice & WhatsApp Delivery
// ============================================================================
function setupFinalizeStage() {
  if (!activeOrder) return;
  const o = activeOrder;

  document.getElementById('finalOrderNumberPill').textContent = o.order_number;
  const payPill = document.getElementById('finalPayStatusPill');
  payPill.className = `status-badge pay-${o.payment_status}`;
  payPill.textContent = formatPayBadge(o.payment_status);

  document.getElementById('finalCustomerName').textContent = o.customer;
  document.getElementById('finalTotal').textContent = money(o.total);
  document.getElementById('finalPaid').textContent = money(o.amount_paid);
  document.getElementById('finalDue').textContent = money(o.due);

  // Setup PDF Download Link
  const pdfBtn = document.getElementById('downloadFinalPdfBtn');
  pdfBtn.href = `/api/tailor/orders/${o.id}/pdf`;
  pdfBtn.setAttribute('download', `invoice-${o.order_number}.pdf`);

  // Build Professional WhatsApp Message
  const waMsg = buildWhatsAppMessage(o);
  document.getElementById('whatsappPreviewBubble').textContent = waMsg;
}

function buildWhatsAppMessage(o) {
  const billUrl = `${location.origin}/bill.html?id=${o.id}`;
  const dueD = o.due_date ? new Date(o.due_date).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' }) : 'As agreed';

  const itemsList = (o.items || []).map(it => `• ${it.item_name} (Qty: ${it.quantity}) - ${money(it.amount)}`).join('\n');

  return `Namaste ${o.customer}! 🙏\nYour stitching bill ${o.order_number} from Jolly Ladies Tailor has been generated.\n\nGarments & Services:\n${itemsList}\n\nTotal Amount: ${money(o.total)}\nPaid Amount: ${money(o.amount_paid)}\nBalance Due: ${money(o.due)}\nPromised Delivery: ${dueD}\n\nView and download your digital e-bill anytime:\n${billUrl}\n\nThank you for choosing Jolly Ladies Tailor!\nSouthern Avenue, Kolkata · Phone: 9748546778`;
}

function executeWhatsAppShare() {
  if (!activeOrder) return;
  const phone = whatsappDigits(activeOrder.phone);
  if (!phone) {
    alert('A valid 10-digit customer mobile number is required to send WhatsApp bill.');
    return;
  }
  const msg = buildWhatsAppMessage(activeOrder);
  const waUrl = `https://wa.me/${phone}?text=${encodeURIComponent(msg)}`;
  window.open(waUrl, '_blank', 'noopener,noreferrer');
  setNotice('#workspaceNotice', `WhatsApp opened for ${escapeHTML(activeOrder.customer)}. Download the PDF invoice if you wish to attach it.`, true);
}

function copyWhatsAppMessage() {
  if (!activeOrder) return;
  const msg = buildWhatsAppMessage(activeOrder);
  navigator.clipboard.writeText(msg).then(() => {
    alert('WhatsApp message copied to clipboard!');
  }).catch(() => {
    prompt('Copy your WhatsApp message below:', msg);
  });
}

async function quickShareWhatsApp(orderId) {
  try {
    const o = await API.get('/api/tailor/orders/' + orderId);
    activeOrder = o;
    executeWhatsAppShare();
  } catch (err) {
    alert('WhatsApp delivery error: ' + err.message);
  }
}

async function printFinalOrderInvoice() {
  if (!activeOrder) return;
  const o = activeOrder;
  const w = window.open('', '_blank');
  w.document.write(`
    <!doctype html>
    <html>
    <head>
      <meta charset="utf-8">
      <title>Invoice - ${escapeHTML(o.order_number)}</title>
      <style>
        body { font-family: Inter, Arial, sans-serif; padding: 24px; color: #333; max-width: 800px; margin: 0 auto; }
        .header { text-align: center; border-bottom: 2px solid #400849; padding-bottom: 14px; margin-bottom: 20px; }
        .header h1 { margin: 0; color: #400849; font-family: Georgia, serif; }
        .meta { display: flex; justify-content: space-between; margin-bottom: 18px; font-size: 14px; }
        table { width: 100%; border-collapse: collapse; margin: 16px 0; }
        th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
        th { background: #fdf2f8; color: #400849; }
        .totals { width: 280px; margin-left: auto; font-size: 14px; line-height: 1.8; margin-top: 16px; }
        @media print { body { padding: 0; } }
      </style>
    </head>
    <body>
      <div class="header">
        <h1>Jolly Ladies Tailor</h1>
        <p style="margin:4px 0">Custom Ladies Tailoring & Alteration Specialist · Southern Avenue, Kolkata</p>
        <p style="margin:4px 0"><strong>Official Invoice: ${escapeHTML(o.order_number)}</strong></p>
      </div>
      <div class="meta">
        <div>
          <p><strong>Customer:</strong> ${escapeHTML(o.customer)} (${escapeHTML(o.phone)})</p>
          ${o.address ? `<p><strong>Address:</strong> ${escapeHTML(o.address)}</p>` : ''}
        </div>
        <div style="text-align:right">
          <p><strong>Order Date:</strong> ${new Date(o.created_at).toLocaleDateString('en-IN')}</p>
          <p><strong>Promised Delivery:</strong> ${o.due_date ? escapeHTML(o.due_date) : 'As agreed'}</p>
        </div>
      </div>
      <table>
        <thead>
          <tr><th>Item / Service</th><th>Rate</th><th>Qty</th><th>Total</th></tr>
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
        <div style="display:flex;justify-content:space-between"><span>Subtotal:</span><span>${money(o.subtotal)}</span></div>
        <div style="display:flex;justify-content:space-between"><span>Discount:</span><span>-${money(o.discount)}</span></div>
        <div style="display:flex;justify-content:space-between;font-size:16px;font-weight:700;border-top:1px solid #333;margin-top:4px;padding-top:4px">
          <span>Net Total:</span><span>${money(o.total)}</span>
        </div>
        <div style="display:flex;justify-content:space-between;color:green"><span>Paid:</span><span>${money(o.amount_paid)}</span></div>
        <div style="display:flex;justify-content:space-between;color:#b30000;font-weight:700;border-top:1px dashed #ccc;padding-top:4px">
          <span>Balance Due:</span><span>${money(o.due)}</span>
        </div>
      </div>
      <p style="text-align:center;margin-top:34px;font-size:12px;color:#777">Thank you for tailoring with Jolly Ladies Tailor! (Phone: +91 9748546778)</p>
    </body>
    </html>
  `);
  w.document.close();
  w.focus();
  setTimeout(() => w.print(), 350);
}

// ============================================================================
// RECENT SHOP-WIDE ORDERS
// ============================================================================
async function loadRecentShopOrders() {
  try {
    shopOrders = await API.get('/api/tailor/orders');
    const tbody = document.getElementById('recentShopOrdersRows');
    if (!tbody) return;
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
            <button class="btn ghost btn-sm" type="button" onclick="viewOrderAuthoritativeBill('${o.id}')">Invoice</button>
          </div>
        </td>
      </tr>
    `).join('');
  } catch (err) {
    console.error('Failed to load shop orders:', err);
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

// ============================================================================
// UTILITIES & HELPERS
// ============================================================================
function whatsappDigits(raw) {
  if (!raw) return '';
  const digits = String(raw).replace(/\D/g, '');
  if (digits.length === 10) return '91' + digits;
  if (digits.length === 12 && digits.startsWith('91')) return digits;
  if (digits.length > 10 && digits.startsWith('0')) return '91' + digits.substring(1);
  return digits.length >= 10 ? digits : '';
}

function formatPayBadge(status) {
  if (status === 'PAID') return 'Fully Paid';
  if (status === 'PARTIALLY_PAID') return 'Partially Paid';
  if (status === 'REFUNDED') return 'Refunded';
  return 'Payment Due';
}

function escapeHTML(str) {
  if (str == null) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function money(amount) {
  const n = Number(amount || 0);
  return '₹' + n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function setNotice(selector, text, isSuccess = false) {
  const el = document.querySelector(selector);
  if (!el) return;
  el.textContent = text;
  el.hidden = !text;
  el.className = 'notice ' + (isSuccess ? 'success' : 'error');
  if (isSuccess) {
    setTimeout(() => { if (el) el.hidden = true; }, 6000);
  }
}

// ============================================================================
// INITIALIZATION & EVENT BINDINGS
// ============================================================================
document.addEventListener('DOMContentLoaded', () => {
  // Auth Form
  const loginForm = document.getElementById('loginForm');
  if (loginForm) loginForm.addEventListener('submit', doLogin);

  const logoutBtn = document.getElementById('logout');
  if (logoutBtn) logoutBtn.addEventListener('click', logout);

  // Top Customer Switch button
  const switchTop = document.getElementById('switchCustomerTopBtn');
  if (switchTop) switchTop.addEventListener('click', switchBackToCustomerSearch);

  // Stage 1 Search bindings
  const searchInput = document.getElementById('customerSearchInput');
  if (searchInput) {
    searchInput.addEventListener('input', e => {
      clearTimeout(searchDebounceTimer);
      searchDebounceTimer = setTimeout(() => searchCustomers(e.target.value.trim()), 250);
    });
  }

  const phoneInput = document.getElementById('newCustPhone');
  if (phoneInput) {
    phoneInput.addEventListener('input', e => checkDuplicatePhone(e.target.value.trim()));
  }

  const newCustForm = document.getElementById('newCustomerForm');
  if (newCustForm) newCustForm.addEventListener('submit', handleRegisterCustomer);

  const editCustForm = document.getElementById('editCustomerForm');
  if (editCustForm) editCustForm.addEventListener('submit', handleSaveCustomerEdits);

  const recMeasureForm = document.getElementById('recordMeasurementForm');
  if (recMeasureForm) recMeasureForm.addEventListener('submit', handleSaveMeasurement);

  const measureGarmentSel = document.getElementById('measureGarmentSelect');
  if (measureGarmentSel) {
    measureGarmentSel.addEventListener('change', e => drawDynamicMeasurementFields(e.target.value));
  }

  const measureUnitSel = document.getElementById('measureUnitSelect');
  if (measureUnitSel) {
    measureUnitSel.addEventListener('change', () => drawDynamicMeasurementFields(val('measureGarmentSelect')));
  }

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
