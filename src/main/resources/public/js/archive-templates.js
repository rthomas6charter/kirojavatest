document.addEventListener('DOMContentLoaded', function () {
    var listEl = document.getElementById('at-list');
    var form = document.getElementById('at-form');
    var formTitle = document.getElementById('at-form-title');
    var nameInput = document.getElementById('at-name');
    var capacityInput = document.getElementById('at-capacity');
    var formatSelect = document.getElementById('at-format');
    var submitBtn = document.getElementById('at-submit-btn');
    var cancelBtn = document.getElementById('at-cancel-btn');
    var refreshBtn = document.getElementById('at-refresh-btn');
    if (!listEl || !form) return;

    var templates = [];
    var editingIndex = -1;

    var FORMAT_LABELS = {
        tarFileLists: 'tar File Lists',
        imgBurnProject: 'ImgBurn Project Files',
        isoScript: 'ISO Image Generation Script'
    };

    refreshBtn.addEventListener('click', function () { loadAll(); });

    // Preset capacity buttons
    document.querySelectorAll('.at-preset').forEach(function (btn) {
        btn.addEventListener('click', function () {
            capacityInput.value = btn.getAttribute('data-mb');
        });
    });

    function loadAll() {
        fetch('/api/archive-templates')
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (data) {
                templates = Array.isArray(data) ? data : [];
                renderList();
            })
            .catch(function () {
                listEl.innerHTML = '<p class="empty-state">Failed to load.</p>';
            });
    }

    function renderList() {
        if (templates.length === 0) {
            listEl.innerHTML = '<p class="empty-state">No archive templates yet.</p>';
            return;
        }
        var table = document.createElement('table');
        table.className = 'data-table';
        var thead = document.createElement('thead');
        thead.innerHTML = '<tr><th>Name</th><th>Capacity</th><th>Output Format</th><th></th></tr>';
        table.appendChild(thead);

        var tbody = document.createElement('tbody');
        templates.forEach(function (tpl, idx) {
            var tr = document.createElement('tr');
            var cap = tpl.mediaCapacityMB ? Number(tpl.mediaCapacityMB).toLocaleString() + ' MB' : '—';
            var fmt = FORMAT_LABELS[tpl.outputFormat] || tpl.outputFormat || '—';

            tr.innerHTML = '<td>' + esc(tpl.name || '') + '</td>'
                + '<td>' + esc(cap) + '</td>'
                + '<td>' + esc(fmt) + '</td>'
                + '<td class="conn-actions"></td>';

            var actions = tr.querySelector('.conn-actions');

            var editBtn = document.createElement('button');
            editBtn.className = 'icon-btn-sm';
            editBtn.title = 'Edit';
            editBtn.innerHTML = '<span class="material-icons">edit</span>';
            editBtn.style.color = '#5d4037';
            editBtn.addEventListener('click', function () { startEdit(idx); });
            actions.appendChild(editBtn);

            var delBtn = document.createElement('button');
            delBtn.className = 'icon-btn-sm';
            delBtn.title = 'Delete';
            delBtn.innerHTML = '<span class="material-icons">delete</span>';
            delBtn.style.color = '#c62828';
            delBtn.addEventListener('click', function () { deleteTemplate(idx); });
            actions.appendChild(delBtn);

            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        listEl.innerHTML = '';
        listEl.appendChild(table);
    }

    function resetForm() {
        editingIndex = -1;
        form.reset();
        formTitle.textContent = 'Create Archive Template';
        submitBtn.innerHTML = '<span class="material-icons btn-icon">add</span> Create Template';
        cancelBtn.style.display = 'none';
    }

    function startEdit(idx) {
        var tpl = templates[idx];
        editingIndex = idx;
        formTitle.textContent = 'Edit Archive Template';
        submitBtn.innerHTML = '<span class="material-icons btn-icon">save</span> Save Template';
        cancelBtn.style.display = '';

        nameInput.value = tpl.name || '';
        capacityInput.value = tpl.mediaCapacityMB || '';
        formatSelect.value = tpl.outputFormat || 'tarFileLists';

        form.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    cancelBtn.addEventListener('click', resetForm);

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        var payload = {
            name: nameInput.value.trim(),
            mediaCapacityMB: parseInt(capacityInput.value, 10) || 0,
            outputFormat: formatSelect.value
        };

        var url = editingIndex >= 0 ? '/api/archive-templates/' + editingIndex : '/api/archive-templates';
        var method = editingIndex >= 0 ? 'PUT' : 'POST';

        fetch(url, {
            method: method,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function () { resetForm(); loadAll(); })
        .catch(function (err) { alert('Failed to save: ' + err.message); });
    });

    function deleteTemplate(idx) {
        if (!confirm('Delete this archive template?')) return;
        fetch('/api/archive-templates/' + idx, { method: 'DELETE' })
            .then(function () { loadAll(); })
            .catch(function (err) { alert('Failed: ' + err.message); });
    }

    function esc(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    loadAll();
});
