let statData = {};
let categories = [];
let products = [];
let users = [];
let requests = [];
let adminCustomers = [];
let adminPayments = [];

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
        <td><a class="btn light" href="/bill.html?id=${o.id}" target="_blank" rel="noopener" style="padding:4px 8px;font-size:12px">View e-Bill ↗</a></td>
      </tr>
    `).join('') : '<tr><td colspan="9">No orders for this customer.</td></tr>';

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
        <div class="muted">${escapeHTML(c.description || 'No description')}</div>
      </div>
      ${c.active
        ? `<button class="btn ghost" type="button" data-deactivate-category="${c.id}">Deactivate</button>`
        : '<span class="pill orange">Inactive</span>'}
    </div>
  `).join('') || '<p class="muted">No categories defined yet.</p>';

  document.querySelectorAll('[data-deactivate-category]').forEach(b => {
    b.onclick = async () => {
      try {
        await API.delete('/api/admin/categories/' + b.dataset.deactivateCategory);
        await loadCategories();
      } catch (e) {
        setNotice('#categoryNotice', e.message);
      }
    };
  });
}

async function loadProducts() {
  products = await API.get('/api/admin/products');
  const tbody = document.getElementById('productsRows');

  tbody.innerHTML = products.map(p => `
    <tr>
      <td><strong>${escapeHTML(p.name)}</strong><br><small class="muted">${escapeHTML(p.description || '')}</small></td>
      <td><span class="pill" style="font-size:11px">${p.product_type === 'READY_MADE' ? 'Ready-Made' : 'Service'}</span></td>
      <td>${escapeHTML(p.category || '—')}</td>
      <td><strong>${money(p.default_rate)}</strong></td>
      <td>${p.product_type === 'READY_MADE' ? p.stock_quantity : '—'}</td>
      <td>${p.active ? '<span class="pill">Active</span>' : '<span class="pill orange">Inactive</span>'}</td>
      <td>
        ${p.imageUrl ? `<img src="${escapeHTML(p.imageUrl)}" alt="" style="width:40px;height:40px;object-fit:cover;border-radius:6px;vertical-align:middle;margin-right:6px">` : ''}
        <label class="btn ghost" style="display:inline-flex;padding:6px 10px;font-size:12px;cursor:pointer">
          Upload
          <input type="file" accept="image/png,image/jpeg,image/webp,image/gif" data-image="${p.id}" hidden>
        </label>
      </td>
      <td>
        <button class="btn ghost" type="button" data-toggle-product="${p.id}" data-active="${p.active}">
          ${p.active ? 'Deactivate' : 'Activate'}
        </button>
      </td>
    </tr>
  `).join('') || '<tr><td colspan="8">No products/services configured yet.</td></tr>';

  document.querySelectorAll('[data-toggle-product]').forEach(b => {
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

  document.querySelectorAll('[data-image]').forEach(inp => {
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

async function addProduct(e) {
  e.preventDefault();
  try {
    await API.post('/api/admin/products', {
      name: av('pName'),
      categoryId: av('pCategory') || null,
      productType: av('pType') || 'SERVICE',
      stockQuantity: Number(av('pStock') || 0),
      defaultRate: Number(av('pRate') || 0),
      description: av('pDescription')
    });
    setNotice('#productNotice', 'Item created successfully.', true);
    e.target.reset();
    document.getElementById('pRate').value = '0';
    document.getElementById('pStock').value = '0';
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

async function loadUsers() {
  users = await API.get('/api/admin/users');
  const list = document.getElementById('usersList');

  list.innerHTML = users.map(u => `
    <div class="card" style="padding:14px;display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap">
      <div>
        <strong>${escapeHTML(u.username)}</strong>
        <div class="muted">${escapeHTML(u.role)} · ${u.active ? '<span style="color:var(--green)">Active</span>' : '<span style="color:#c00">Disabled</span>'} · Created ${new Date(u.created_at).toLocaleDateString()}</div>
      </div>
      <div class="form-actions" style="margin:0">
        <button class="btn ghost" type="button" data-user-role="${u.id}" data-current-role="${u.role}">
          Switch to ${u.role === 'ADMIN' ? 'TAILOR' : 'ADMIN'}
        </button>
        <button class="btn ghost" type="button" data-user-reset-pw="${u.id}">Reset Password</button>
        <button class="btn ghost" type="button" data-user-active="${u.id}" data-active="${u.active}">
          ${u.active ? 'Disable' : 'Enable'}
        </button>
      </div>
    </div>
  `).join('') || '<p class="muted">No staff accounts.</p>';

  document.querySelectorAll('[data-user-active]').forEach(b => {
    b.onclick = async () => {
      if (!confirm('Change status for this staff account?')) return;
      try {
        await API.patch('/api/admin/users/' + b.dataset.userActive + '/active', {
          active: b.dataset.active !== 'true'
        });
        await loadUsers();
      } catch (e) {
        alert(e.message);
      }
    };
  });

  document.querySelectorAll('[data-user-role]').forEach(b => {
    b.onclick = async () => {
      const newRole = b.dataset.currentRole === 'ADMIN' ? 'TAILOR' : 'ADMIN';
      if (!confirm(`Change role to ${newRole}?`)) return;
      try {
        await API.patch('/api/admin/users/' + b.dataset.userRole + '/role', { role: newRole });
        await loadUsers();
      } catch (e) {
        alert(e.message);
      }
    };
  });

  document.querySelectorAll('[data-user-reset-pw]').forEach(b => {
    b.onclick = async () => {
      const newPw = prompt('Enter a new password for this user (minimum 12 characters):');
      if (!newPw) return;
      if (newPw.length < 12) {
        alert('Password must be at least 12 characters.');
        return;
      }
      try {
        await API.patch('/api/admin/users/' + b.dataset.userResetPw + '/password', { password: newPw });
        alert('Password reset successfully!');
      } catch (e) {
        alert(e.message);
      }
    };
  });
}

async function addUser(e) {
  e.preventDefault();
  try {
    await API.post('/api/admin/users', {
      username: av('uName'),
      password: av('uPassword'),
      role: av('uRole')
    });
    setNotice('#userNotice', 'Staff account created successfully.', true);
    e.target.reset();
    await loadUsers();
  } catch (err) {
    setNotice('#userNotice', err.message);
  }
}

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

function exportStatsCSV() {
  const rows = [
    ['Metric', 'Value'],
    ['Reporting Period', statData.range || ''],
    ['Sales Billed', statData.billed || 0],
    ['Payments Received', statData.collected || 0],
    ['Outstanding Balance', statData.due || 0],
    ['Total Orders', statData.orders || 0],
    ['Total Customers', statData.customers || 0],
    ['Active Services', statData.products || 0],
    ['New Enquiries', statData.requests || 0],
    ['Average Order Value', Number(statData.orders) ? (Number(statData.billed) / Number(statData.orders)).toFixed(2) : '0']
  ];
  const csv = rows.map(r => r.map(v => '"' + String(v).replace(/"/g, '""') + '"').join(',')).join('\r\n');
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
  a.download = `jolly-tailor-kpi-summary-${statData.range || 'summary'}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
}

function logout() {
  API.post('/api/auth/logout', {}).finally(() => location.reload());
}

// Event Bindings
document.getElementById('loginForm').addEventListener('submit', login);
document.getElementById('logout').addEventListener('click', logout);
document.getElementById('range').addEventListener('change', loadStats);
document.getElementById('productForm').addEventListener('submit', addProduct);
document.getElementById('categoryForm').addEventListener('submit', addCategory);
document.getElementById('userForm').addEventListener('submit', addUser);
document.getElementById('exportStats').addEventListener('click', exportStatsCSV);
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
