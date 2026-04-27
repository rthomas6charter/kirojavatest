document.addEventListener('DOMContentLoaded', function () {
    var listEl = document.getElementById('conn-list');
    var addForm = document.getElementById('conn-add-form');
    var typeSelect = document.getElementById('conn-type');
    var fieldsEl = document.getElementById('conn-fields');
    var backdrop = document.getElementById('conn-dialog-backdrop');
    var editForm = document.getElementById('conn-edit-form');
    var closeBtn = document.getElementById('conn-dialog-close');
    var saveBtn = document.getElementById('conn-save-btn');
    var cancelBtn = document.getElementById('conn-cancel-btn');
    if (!listEl) return;

    var connections = [];
    var editingIndex = -1;

    var FIELD_DEFS = {
        smb: [
            { name: 'name', label: 'Name', placeholder: 'My NAS', required: true },
            { name: 'host', label: 'Host', placeholder: '192.168.1.100', required: true },
            { name: 'share', label: 'Share', placeholder: 'photos', required: true },
            { name: 'username', label: 'Username', placeholder: 'guest' },
            { name: 'password', label: 'Password', placeholder: '', type: 'password' },
            { name: 'domain', label: 'Domain', placeholder: 'WORKGROUP' }
        ],
        sftp: [
            { name: 'name', label: 'Name', placeholder: 'My Server', required: true },
            { name: 'host', label: 'Host', placeholder: '192.168.1.100', required: true },
            { name: 'port', label: 'Port', placeholder: '22' },
            { name: 'remotePath', label: 'Remote Path', placeholder: '/home/user/files' },
            { name: 'username', label: 'Username', placeholder: 'user', required: true },
            { name: 'password', label: 'Password', placeholder: '', type: 'password' },
            { name: 'keyFile', label: 'Key File', placeholder: '~/.ssh/id_rsa' }
        ]
    };

    var TYPE_ICONS = {
        smb: 'folder_shared',
        sftp: 'cloud_upload'
    };

    // Render add-form fields based on selected type
    function renderAddFields() {
        var type = typeSelect.value;
        fieldsEl.innerHTML = '';
        FIELD_DEFS[type].forEach(function (f) {
            var div = document.createElement('div');
            div.innerHTML = '<label class="form-label">' + esc(f.label) + '</label>'
                + '<input class="form-input" name="' + f.name + '"'
                + ' placeholder="' + esc(f.placeholder || '') + '"'
                + (f.type === 'password' ? ' type="password"' : ' type="text"')
                + (f.required ? ' required' : '') + '>';
            fieldsEl.appendChild(div);
        });
    }

    typeSelect.addEventListener('change', renderAddFields);
    renderAddFields();

    loadConnections();

    // Refresh button
    var refreshBtn = document.getElementById('conn-refresh-btn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', function () { loadConnections(); });
    }

    // Add form submit
    addForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var type = typeSelect.value;
        var conn = { type: type, active: false };
        FIELD_DEFS[type].forEach(function (f) {
            var input = addForm.querySelector('input[name="' + f.name + '"]');
            if (input) conn[f.name] = input.value;
        });

        fetch('/api/connections', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(conn)
        })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function () { addForm.reset(); renderAddFields(); loadConnections(); })
        .catch(function (err) { alert('Failed to add: ' + err.message); });
    });

    function loadConnections() {
        fetch('/api/connections')
            .then(function (res) { return res.ok ? res.json() : []; })
            .then(function (data) {
                connections = Array.isArray(data) ? data : [];
                renderList();
            })
            .catch(function () {
                listEl.innerHTML = '<p class="empty-state">Failed to load connections.</p>';
            });
    }

    function renderList() {
        if (connections.length === 0) {
            listEl.innerHTML = '<p class="empty-state">No connections configured.</p>';
            return;
        }

        var table = document.createElement('table');
        table.className = 'data-table';

        var thead = document.createElement('thead');
        thead.innerHTML = '<tr><th></th><th>Name</th><th>Host</th><th>Details</th><th>Username</th><th>Status</th><th></th></tr>';
        table.appendChild(thead);

        var tbody = document.createElement('tbody');
        connections.forEach(function (conn, idx) {
            var tr = document.createElement('tr');
            var isActive = conn.active === true || conn.active === 'true';
            var type = conn.type || 'smb';
            var icon = TYPE_ICONS[type] || 'lan';
            var detail = type === 'sftp'
                ? (conn.remotePath || '') + (conn.port ? ' :' + conn.port : '')
                : (conn.share || '') + (conn.domain ? ' (' + conn.domain + ')' : '');

            tr.innerHTML = '<td><span class="material-icons conn-type-icon" title="' + type.toUpperCase() + '">' + icon + '</span></td>'
                + '<td>' + esc(conn.name || '') + '</td>'
                + '<td>' + esc(conn.host || '') + '</td>'
                + '<td>' + esc(detail) + '</td>'
                + '<td>' + esc(conn.username || '') + '</td>'
                + '<td><span class="status-chip ' + (isActive ? 'status-active' : 'status-inactive') + '">'
                + (isActive ? 'Active' : 'Inactive') + '</span></td>'
                + '<td class="conn-actions"></td>';

            var actions = tr.querySelector('.conn-actions');

            var toggleBtn = document.createElement('button');
            toggleBtn.className = 'icon-btn-sm';
            toggleBtn.title = isActive ? 'Deactivate' : 'Activate';
            toggleBtn.innerHTML = '<span class="material-icons">' + (isActive ? 'toggle_on' : 'toggle_off') + '</span>';
            toggleBtn.style.color = isActive ? '#2e7d32' : '#9e9e9e';
            toggleBtn.addEventListener('click', function () { toggleActive(idx, !isActive); });
            actions.appendChild(toggleBtn);

            var editBtn = document.createElement('button');
            editBtn.className = 'icon-btn-sm';
            editBtn.title = 'Edit';
            editBtn.innerHTML = '<span class="material-icons">edit</span>';
            editBtn.style.color = '#5d4037';
            editBtn.addEventListener('click', function () { openEdit(idx); });
            actions.appendChild(editBtn);

            var delBtn = document.createElement('button');
            delBtn.className = 'icon-btn-sm';
            delBtn.title = 'Delete';
            delBtn.innerHTML = '<span class="material-icons">delete</span>';
            delBtn.style.color = '#c62828';
            delBtn.addEventListener('click', function () { deleteConn(idx); });
            actions.appendChild(delBtn);

            tbody.appendChild(tr);
        });
        table.appendChild(tbody);

        listEl.innerHTML = '';
        listEl.appendChild(table);
    }

    function toggleActive(idx, active) {
        fetch('/api/connections/' + idx, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ active: active })
        })
        .then(function () { loadConnections(); })
        .catch(function (err) { alert('Failed: ' + err.message); });
    }

    function deleteConn(idx) {
        if (!confirm('Delete this connection?')) return;
        fetch('/api/connections/' + idx, { method: 'DELETE' })
            .then(function () { loadConnections(); })
            .catch(function (err) { alert('Failed: ' + err.message); });
    }

    function openEdit(idx) {
        editingIndex = idx;
        var conn = connections[idx];
        var type = conn.type || 'smb';
        var fields = FIELD_DEFS[type] || FIELD_DEFS.smb;
        editForm.innerHTML = '';

        // Show type as read-only
        var typeLabel = document.createElement('label');
        typeLabel.className = 'form-label';
        typeLabel.textContent = 'Type';
        var typeInput = document.createElement('input');
        typeInput.className = 'form-input';
        typeInput.value = type.toUpperCase();
        typeInput.readOnly = true;
        typeInput.style.color = '#9e9e9e';
        editForm.appendChild(typeLabel);
        editForm.appendChild(typeInput);

        fields.forEach(function (f) {
            var label = document.createElement('label');
            label.className = 'form-label';
            label.textContent = f.label;
            var input = document.createElement('input');
            input.className = 'form-input';
            input.name = f.name;
            input.type = f.type === 'password' ? 'password' : 'text';
            input.value = conn[f.name] || '';
            editForm.appendChild(label);
            editForm.appendChild(input);
        });
        backdrop.style.display = '';
    }

    function closeDialog() {
        backdrop.style.display = 'none';
        editingIndex = -1;
    }

    function saveEdit() {
        if (editingIndex < 0) return;
        var updates = {};
        var inputs = editForm.querySelectorAll('input:not([readonly])');
        inputs.forEach(function (input) { updates[input.name] = input.value; });

        fetch('/api/connections/' + editingIndex, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(updates)
        })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function () { closeDialog(); loadConnections(); })
        .catch(function (err) { alert('Failed to save: ' + err.message); });
    }

    closeBtn.addEventListener('click', closeDialog);
    cancelBtn.addEventListener('click', closeDialog);
    saveBtn.addEventListener('click', saveEdit);
    backdrop.addEventListener('click', function (e) { if (e.target === backdrop) closeDialog(); });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && backdrop.style.display !== 'none') closeDialog();
    });

    function esc(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }
});
