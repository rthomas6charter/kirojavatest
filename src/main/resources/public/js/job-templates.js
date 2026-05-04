document.addEventListener('DOMContentLoaded', function () {
    var listEl = document.getElementById('jt-list');
    var form = document.getElementById('jt-form');
    var formTitle = document.getElementById('jt-form-title');
    var nameInput = document.getElementById('jt-name');
    var reorganizeCb = document.getElementById('jt-reorganize');
    var removeDupsCb = document.getElementById('jt-remove-dups');
    var notifyCb = document.getElementById('jt-notify');
    var emailGroup = document.getElementById('jt-email-group');
    var emailInput = document.getElementById('jt-email');
    var toConnRadio = document.getElementById('jt-to-conn-radio');
    var toConnLabel = document.getElementById('jt-to-conn-label');
    var connGroup = document.getElementById('jt-conn-group');
    var connSelect = document.getElementById('jt-connection');
    var srcConnRadio = document.getElementById('jt-src-conn-radio');
    var srcConnLabel = document.getElementById('jt-src-conn-label');
    var srcConnGroup = document.getElementById('jt-src-conn-group');
    var srcConnSelect = document.getElementById('jt-src-connection');
    var sourceWrap = document.getElementById('jt-source-wrap');
    var submitBtn = document.getElementById('jt-submit-btn');
    var cancelBtn = document.getElementById('jt-cancel-btn');
    var refreshBtn = document.getElementById('jt-refresh-btn');
    var targetWrap = document.getElementById('jt-target-wrap');
    if (!listEl || !form) return;

    var templates = [];
    var connections = [];
    var editingIndex = -1;

    // Toggle email field visibility
    notifyCb.addEventListener('change', function () {
        emailGroup.style.display = notifyCb.checked ? '' : 'none';
        if (notifyCb.checked) emailInput.required = true;
        else { emailInput.required = false; emailInput.value = ''; }
    });

    // Toggle connection select visibility based on target radio
    function onTargetChange() {
        var selected = form.querySelector('input[name="target"]:checked');
        connGroup.style.display = selected && selected.value === 'toConnection' ? '' : 'none';
        if (selected && selected.value === 'toConnection') connSelect.required = true;
        else connSelect.required = false;
    }
    form.querySelectorAll('input[name="target"]').forEach(function (r) {
        r.addEventListener('change', onTargetChange);
    });

    // Toggle source connection select visibility
    function onSourceChange() {
        var selected = form.querySelector('input[name="source"]:checked');
        srcConnGroup.style.display = selected && selected.value === 'fromConnection' ? '' : 'none';
        if (selected && selected.value === 'fromConnection') srcConnSelect.required = true;
        else srcConnSelect.required = false;
    }
    form.querySelectorAll('input[name="source"]').forEach(function (r) {
        r.addEventListener('change', onSourceChange);
    });

    refreshBtn.addEventListener('click', function () { loadAll(); });

    function loadAll() {
        Promise.all([
            fetch('/api/job-templates').then(function (r) { return r.ok ? r.json() : []; }),
            fetch('/api/connections').then(function (r) { return r.ok ? r.json() : []; })
        ]).then(function (results) {
            templates = Array.isArray(results[0]) ? results[0] : [];
            connections = Array.isArray(results[1]) ? results[1] : [];
            renderList();
            updateConnectionOptions();
        }).catch(function () {
            listEl.innerHTML = '<p class="empty-state">Failed to load.</p>';
        });
    }

    function updateConnectionOptions() {
        connSelect.innerHTML = '';
        srcConnSelect.innerHTML = '';
        var named = connections.filter(function (c) { return c.name; });
        if (named.length === 0) {
            toConnRadio.disabled = true;
            toConnLabel.style.opacity = '0.5';
            toConnLabel.style.cursor = 'not-allowed';
            targetWrap.title = 'Create a Connection in Admin \u2192 Connections before using "To Connection".';
            if (toConnRadio.checked) {
                form.querySelector('input[name="target"][value="inPlace"]').checked = true;
                onTargetChange();
            }
            srcConnRadio.disabled = true;
            srcConnLabel.style.opacity = '0.5';
            srcConnLabel.style.cursor = 'not-allowed';
            sourceWrap.title = 'Create a Connection in Admin \u2192 Connections before using "From Connection".';
            if (srcConnRadio.checked) {
                form.querySelector('input[name="source"][value="defaultDataDir"]').checked = true;
                onSourceChange();
            }
        } else {
            toConnRadio.disabled = false;
            toConnLabel.style.opacity = '';
            toConnLabel.style.cursor = '';
            targetWrap.title = '';
            srcConnRadio.disabled = false;
            srcConnLabel.style.opacity = '';
            srcConnLabel.style.cursor = '';
            sourceWrap.title = '';
            named.forEach(function (c) {
                var label = c.name + ' (' + (c.type || 'smb').toUpperCase() + ' \u2014 ' + (c.host || c.subPath || '') + ')';
                var opt = document.createElement('option');
                opt.value = c.name;
                opt.textContent = label;
                connSelect.appendChild(opt);
                var opt2 = document.createElement('option');
                opt2.value = c.name;
                opt2.textContent = label;
                srcConnSelect.appendChild(opt2);
            });
        }
    }

    function renderList() {
        if (templates.length === 0) {
            listEl.innerHTML = '<p class="empty-state">No job templates yet.</p>';
            return;
        }
        var table = document.createElement('table');
        table.className = 'data-table';
        var thead = document.createElement('thead');
        thead.innerHTML = '<tr><th>Name</th><th>Source</th><th>Options</th><th>Target</th><th></th></tr>';
        table.appendChild(thead);

        var tbody = document.createElement('tbody');
        templates.forEach(function (tpl, idx) {
            var tr = document.createElement('tr');
            var opts = [];
            if (tpl.reorganize) opts.push('Reorganize');
            if (tpl.removeDuplicates) opts.push('Remove Dups');
            if (tpl.sendNotification) opts.push('Notify (' + esc(tpl.notificationEmail || '') + ')');

            var targetText = tpl.target === 'toConnection'
                ? 'To Connection: ' + esc(tpl.connectionName || '—')
                : 'In Place';

            var sourceText = tpl.source === 'fromConnection'
                ? 'Connection: ' + esc(tpl.sourceConnectionName || '—')
                : 'Default Data Dir';

            tr.innerHTML = '<td>' + esc(tpl.name || '') + '</td>'
                + '<td>' + sourceText + '</td>'
                + '<td>' + (opts.length ? esc(opts.join(', ')) : '<span style="color:#9e9e9e">None</span>') + '</td>'
                + '<td>' + targetText + '</td>'
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
        emailGroup.style.display = 'none';
        connGroup.style.display = 'none';
        srcConnGroup.style.display = 'none';
        emailInput.required = false;
        connSelect.required = false;
        srcConnSelect.required = false;
        formTitle.textContent = 'Create Job Template';
        submitBtn.innerHTML = '<span class="material-icons btn-icon">add</span> Create Template';
        cancelBtn.style.display = 'none';
        updateConnectionOptions();
    }

    function startEdit(idx) {
        var tpl = templates[idx];
        editingIndex = idx;
        formTitle.textContent = 'Edit Job Template';
        submitBtn.innerHTML = '<span class="material-icons btn-icon">save</span> Save Template';
        cancelBtn.style.display = '';

        nameInput.value = tpl.name || '';
        reorganizeCb.checked = !!tpl.reorganize;
        removeDupsCb.checked = !!tpl.removeDuplicates;

        // Restore source
        var srcVal = tpl.source === 'fromConnection' ? 'fromConnection' : 'defaultDataDir';
        var srcRadio = form.querySelector('input[name="source"][value="' + srcVal + '"]');
        if (srcRadio && !srcRadio.disabled) srcRadio.checked = true;
        else form.querySelector('input[name="source"][value="defaultDataDir"]').checked = true;
        onSourceChange();
        if (tpl.sourceConnectionName) srcConnSelect.value = tpl.sourceConnectionName;

        notifyCb.checked = !!tpl.sendNotification;
        emailGroup.style.display = tpl.sendNotification ? '' : 'none';
        emailInput.value = tpl.notificationEmail || '';
        emailInput.required = !!tpl.sendNotification;

        var targetVal = tpl.target === 'toConnection' ? 'toConnection' : 'inPlace';
        var radio = form.querySelector('input[name="target"][value="' + targetVal + '"]');
        if (radio && !radio.disabled) radio.checked = true;
        else form.querySelector('input[name="target"][value="inPlace"]').checked = true;
        onTargetChange();

        if (tpl.connectionName) connSelect.value = tpl.connectionName;

        // Scroll form into view
        form.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    cancelBtn.addEventListener('click', resetForm);

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        var selected = form.querySelector('input[name="target"]:checked');
        var selectedSrc = form.querySelector('input[name="source"]:checked');
        var payload = {
            name: nameInput.value.trim(),
            source: selectedSrc ? selectedSrc.value : 'defaultDataDir',
            sourceConnectionName: selectedSrc && selectedSrc.value === 'fromConnection' ? srcConnSelect.value : '',
            reorganize: reorganizeCb.checked,
            removeDuplicates: removeDupsCb.checked,
            sendNotification: notifyCb.checked,
            notificationEmail: notifyCb.checked ? emailInput.value.trim() : '',
            target: selected ? selected.value : 'inPlace',
            connectionName: selected && selected.value === 'toConnection' ? connSelect.value : ''
        };

        var url = editingIndex >= 0 ? '/api/job-templates/' + editingIndex : '/api/job-templates';
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
        if (!confirm('Delete this job template?')) return;
        fetch('/api/job-templates/' + idx, { method: 'DELETE' })
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
