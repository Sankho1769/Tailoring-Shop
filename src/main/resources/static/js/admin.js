let statData = {};
let categories = [];
let products = [];
let users = [];
let requests = [];
let adminCustomers = [];
let adminPayments = [];
let currentEditingProductId = null;
let currentViewedMeasurement = null;

const av = id => {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
};

async function login(e) {
  e.preventDefault();
  const username = av('username');
  const password = document.getElementById('password') ? document.getElementById('password').value : '';
  try {
    await API.csrf();
    const me = await API.post('/api/auth/login', { username, password });
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

  await Promise.all([
    loadStats(),
    loadCategories(),
    loadProducts(),
    loadPriceAdjustments(),
    loadAdminCustomers(),
    loadAdminPayments(),
    loadRequests(),
    loadUsers(),
    loadShopSettings()
  ]);
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
    document.getElementById('statRequests').textContent = Number(statData.requests || 0).toLocaleString('en-IN');
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
          <td>${escapeHTML(o.customer)}<br><small class="muted">${escapeHTML(o.phone)}</small></td>
          <td><span class="pill">${escapeHTML(o.status)}</span></td>
          <td><span class="pill ${o.payment_status === 'PAID' ? '' : 'orange'}">${escapeHTML(o.payment_status)}</span></td>
          <td>${money(o.total)}</td>
          <td>${money(o.amount_paid)}</td>
          <td><strong style="color:${Number(o.due) > 0 ? '#b30000' : 'inherit'}">${money(o.due)}</strong></td>
          <td style="white-space:nowrap">
            <a class="btn light btn-sm" href="/bill.html?id=${o.id}" target="_blank" rel="noopener">View e-Bill ↗</a>
            <a class="btn orange btn-sm" href="/api/admin/orders/${o.id}/pdf" download="invoice-${escapeHTML(o.order_number)}.pdf">📄 PDF</a>
            <button class="btn ghost btn-sm" type="button" data-view-measure="${o.id}">📐 Measurements</button>
          </td>
        </tr>
      `).join('') || '<tr><td colspan="8">No recent orders recorded.</td></tr>';

      recentTbody.querySelectorAll('[data-view-measure]').forEach(btn => {
        btn.onclick = () => openAdminMeasurementModal(btn.dataset.viewMeasure);
      });
    }

    document.getElementById('statsNotice').hidden = true;
  } catch (e) {
    setNotice('#statsNotice', e.message);
  }
}

async function loadAdminCustomers(q = '') {
  try {
    adminCustomers = await API.get('/api/admin/customers' + (q ? '?q=' + encodeURIComponent(q) : ''));
    const tbody = document.getElementById('adminCustomersRows');

    tbody.innerHTML = adminCustomers.length ? adminCustomers.map(c => `
      <tr>
        <td><strong>${escapeHTML(c.name)}</strong><br><small class="muted">${escapeHTML(c.address || '')}</small></td>
        <td><a href="https://wa.me/${whatsappDigits(c.phone)}" target="_blank" rel="noopener">${escapeHTML(c.phone)}</a></td>
        <td>${c.total_orders}</td>
        <td>${money(c.total_spent)}</td>
        <td><strong style="color:${Number(c.total_due) > 0 ? 'var(--orange)' : 'inherit'}">${money(c.total_due)}</strong></td>
        <td>${c.last_order_date ? new Date(c.last_order_date).toLocaleDateString() : '—'}</td>
        <td>
          <button class="btn ghost" type="button" data-cust-orders="${c.id}" data-cust-name="${escapeHTML(c.name)}">
            View Orders (${c.total_orders})
          </button>
        </td>
      </tr>
    `).join('') : '<tr><td colspan="7">No customers match the query.</td></tr>';

    tbody.querySelectorAll('[data-cust-orders]').forEach(btn => {
      btn.onclick = () => openCustomerOrders(btn.dataset.custOrders, btn.dataset.custName);
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
        <td><span class="pill">${escapeHTML(o.status)}</span></td>
        <td><span class="pill ${o.payment_status === 'PAID' ? '' : 'orange'}">${escapeHTML(o.payment_status)}</span></td>
        <td>${money(o.total)}</td>
        <td>${money(o.amount_paid)}</td>
        <td><strong>${money(o.due)}</strong></td>
        <td>${o.due_date ? escapeHTML(o.due_date) : '—'}</td>
        <td>${new Date(o.created_at).toLocaleDateString()}</td>
        <td style="white-space:nowrap">
          <a class="btn light btn-sm" href="/bill.html?id=${o.id}" target="_blank" rel="noopener">View e-Bill ↗</a>
          <a class="btn orange btn-sm" href="/api/admin/orders/${o.id}/pdf" download="invoice-${escapeHTML(o.order_number)}.pdf">📄 PDF</a>
          <button class="btn ghost btn-sm" type="button" data-view-measure="${o.id}">📐 Measurements</button>
        </td>
      </tr>
    `).join('') : '<tr><td colspan="9">No orders for this customer.</td></tr>';

    tbody.querySelectorAll('[data-view-measure]').forEach(btn => {
      btn.onclick = () => openAdminMeasurementModal(btn.dataset.viewMeasure);
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
  requests = await API.get('/api/admin/requests');
  const tbody = document.getElementById('requestsRows');

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
      const lastLoginStr = u.last_login_at
        ? new Date(u.last_login_at).toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'short' }) + ' IST'
        : '<span class="muted">Never logged in</span>';
      const createdStr = u.created_at ? new Date(u.created_at).toLocaleDateString('en-IN') : '—';

      return `
        <tr>
          <td><strong>${escapeHTML(u.full_name || u.username)}</strong></td>
          <td><code>${escapeHTML(u.username)}</code></td>
          <td><span class="pill staff-badge-${u.role}">${escapeHTML(u.role)}</span></td>
          <td>
            <span class="staff-active-${u.active}">${u.active ? '● Active' : '○ Disabled'}</span>
            ${isLastAdmin ? '<br><small class="pill" style="font-size:10px;background:#fdeeee;color:#b30000">Sole Admin</small>' : ''}
          </td>
          <td>${createdStr}</td>
          <td>${lastLoginStr}</td>
          <td style="white-space:nowrap">
            <button class="btn light btn-sm" type="button" data-edit-staff="${u.id}">✏️ Edit</button>
            <button class="btn ghost btn-sm" type="button" data-reset-pw-staff="${u.id}" data-staff-user="${escapeHTML(u.username)}">🔑 Reset PW</button>
            <button class="btn ghost btn-sm" type="button" data-toggle-staff-active="${u.id}" data-active="${u.active}" ${isLastAdmin ? 'title="Cannot deactivate sole active admin"' : ''}>
              ${u.active ? 'Disable' : 'Enable'}
            </button>
          </td>
        </tr>
      `;
    }).join('') || '<tr><td colspan="7">No staff accounts found.</td></tr>';

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

    if (!res.hasMeasurement || !res.measurement) {
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
    const m = res.measurement;
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
        <td style="text-transform:capitalize;font-weight:600">${escapeHTML(k.replace(/_/g, ' '))}</td>
        <td style="font-weight:700;color:var(--violet)">${escapeHTML(String(v))} ${escapeHTML(m.unit || 'in')}</td>
      </tr>
    `).join('') || '<tr><td colspan="2" class="muted">No specific dimensions entered.</td></tr>';

    content.innerHTML = `
      <div class="measurement-box">
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
            <tr><th>Measurement Point</th><th>Value</th></tr>
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
  } catch (err) {
    content.innerHTML = `<div class="notice error">Failed to load measurement snapshot: ${escapeHTML(err.message)}</div>`;
  }
}

function closeAdminMeasureModal() {
  document.getElementById('adminMeasurementModal').classList.add('hidden');
}

function printAdminMeasurement() {
  if (!currentViewedMeasurement || !currentViewedMeasurement.measurement) return;
  const res = currentViewedMeasurement;
  const m = res.measurement;
  let dims = {};
  try {
    dims = typeof m.dimensions === 'string' ? JSON.parse(m.dimensions) : (m.dimensions || {});
  } catch (e) {
    dims = {};
  }

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
        table { width: 100%; border-collapse: collapse; margin-top: 12px; }
        th, td { border: 1px solid #ccc; padding: 8px 12px; text-align: left; }
        th { background: #fbf2f9; color: #400849; }
        .notes { margin-top: 16px; padding: 12px; border: 1px dashed #786b7a; border-radius: 8px; font-size: 13px; }
      </style>
    </head>
    <body>
      <div class="header">
        <h1>Jolly Ladies Tailor — Cutting & Measurement Sheet</h1>
        <p style="margin:4px 0">Southern Avenue, Kolkata · Phone: 9748546778</p>
      </div>
      <div class="meta">
        <div>
          <p><strong>Order No:</strong> ${escapeHTML(res.orderNumber || '')}</p>
          <p><strong>Customer:</strong> ${escapeHTML(res.customerName || '')}</p>
        </div>
        <div style="text-align:right">
          <p><strong>Garment:</strong> ${escapeHTML(m.garment_type)}</p>
          <p><strong>Fit Preference:</strong> ${escapeHTML(m.fit_preference || 'Standard')}</p>
          <p><strong>Unit:</strong> ${escapeHTML(m.unit || 'in')}</p>
        </div>
      </div>
      <table>
        <thead>
          <tr><th>Measurement Dimension</th><th>Dimension Value (${escapeHTML(m.unit || 'in')})</th></tr>
        </thead>
        <tbody>
          ${Object.entries(dims).map(([k, v]) => `
            <tr>
              <td style="text-transform:capitalize;font-weight:600">${escapeHTML(k.replace(/_/g, ' '))}</td>
              <td><strong>${escapeHTML(String(v))} ${escapeHTML(m.unit || 'in')}</strong></td>
            </tr>
          `).join('')}
        </tbody>
      </table>
      ${m.notes ? `<div class="notes"><strong>Fitting & Craftsmanship Notes:</strong><br>${escapeHTML(m.notes)}</div>` : ''}
      <p style="text-align:center;margin-top:28px;font-size:12px;color:#777">Printed for workshop tailoring & fitting verification.</p>
    </body>
    </html>
  `);
  w.document.close();
  w.focus();
  setTimeout(() => w.print(), 350);
}

// ============================================================================
// SHOP SETTINGS & GPAY QR
// ============================================================================
async function loadShopSettings() {
  try {
    const s = await API.get('/api/admin/shop-settings');
    document.getElementById('shopName').value = s.shop_name || 'Jolly Ladies Tailor';
    document.getElementById('shopPhone').value = s.shop_phone || '';
    document.getElementById('shopAddress').value = s.shop_address || '';
    document.getElementById('shopWhatsapp').value = s.whatsapp_number || '';
    document.getElementById('shopAdminPhone').value = s.admin_notification_phone || '';
    document.getElementById('shopUpi').value = s.upi_id || '';

    if (s.gpay_qr_key) {
      const pub = await API.get('/api/public/shop');
      if (pub.gpayQrUrl) {
        const img = document.getElementById('shopQrPreview');
        img.src = pub.gpayQrUrl;
        img.classList.remove('hidden');
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
    setNotice('#shopNotice', 'Shop details saved successfully in PostgreSQL.', true);
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
    img.src = result.url;
    img.classList.remove('hidden');
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
document.getElementById('logout').addEventListener('click', logout);
document.getElementById('range').addEventListener('change', loadStats);
document.getElementById('productForm').addEventListener('submit', handleProductSubmit);
document.getElementById('categoryForm').addEventListener('submit', addCategory);
document.getElementById('userForm').addEventListener('submit', addUser);
document.getElementById('refreshProducts').addEventListener('click', () => Promise.all([loadProducts(), loadCategories()]));
document.getElementById('refreshAdjustments').addEventListener('click', loadPriceAdjustments);
document.getElementById('refreshRequests').addEventListener('click', loadRequests);
document.getElementById('shopSettingsForm').addEventListener('submit', saveShopSettings);
document.getElementById('uploadShopQr').addEventListener('click', uploadShopQr);

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
