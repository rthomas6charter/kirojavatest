document.addEventListener('DOMContentLoaded', function () {
    var listEl = document.getElementById('jobs-list');
    var tplSelect = document.getElementById('jobs-create-tpl');
    var createBtn = document.getElementById('jobs-create-btn');
    var refreshBtn = document.getElementById('jobs-refresh-btn');
    var errorBackdrop = document.getElementById('jobs-error-backdrop');
    var errorContent = document.getElementById('jobs-error-content');
    var errorCloseBtn = document.getElementById('jobs-error-close');
    var errorOkBtn = document.getElementById('jobs-error-ok-btn');
    if (!listEl) return;

    var jobs = [];
    var templates = [];

    refreshBtn.addEventListener('click', function () { loadAll(); });
    createBtn.addEventListener('click', function () { createJob(); });

    function loadAll() {
        Promise.all([
            fetch('/api/jobs').then(function (r) { return r.ok ? r.json() : []; }),
            fetch('/api/job-templates').then(function (r) { return r.ok ? r.json() : []; })
        ]).then(function (results) {
            jobs = Array.isArray(results[0]) ? results[0] : [];
            templates = Array.isArray(results[1]) ? results[1] : [];
            renderTemplateSelect();
            renderList();
        }).catch(function () {
            listEl.innerHTML = '<p class="empty-state">Failed to load.</p>';
        });
    }

    function renderTemplateSelect() {
        tplSelect.innerHTML = '';
        if (templates.length === 0) {
            var opt = document.createElement('option');
            opt.value = '';
            opt.textContent = '— No templates available —';
            opt.disabled = true;
            opt.selected = true;
            tplSelect.appendChild(opt);
            createBtn.disabled = true;
            return;
        }
        createBtn.disabled = false;
        templates.forEach(function (tpl, idx) {
            var opt = document.createElement('option');
            opt.value = idx;
            opt.textContent = tpl.name || 'Unnamed Template';
            tplSelect.appendChild(opt);
        });
    }

    function createJob() {
        var idx = parseInt(tplSelect.value, 10);
        if (isNaN(idx)) return;
        fetch('/api/jobs', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ templateIndex: idx })
        })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function () { loadAll(); })
        .catch(function (err) { alert('Failed to create job: ' + err.message); });
    }

    var STATUS_META = {
        created:   { chip: 'status-pending',  label: 'Created',   icon: 'schedule' },
        running:   { chip: 'status-active',   label: 'Running',   icon: 'sync' },
        completed: { chip: 'status-active',   label: 'Completed', icon: 'check_circle' },
        error:     { chip: 'status-inactive', label: 'Error',     icon: 'error' }
    };

    function renderList() {
        if (jobs.length === 0) {
            listEl.innerHTML = '<p class="empty-state">No jobs yet. Create one from a template above.</p>';
            return;
        }

        var table = document.createElement('table');
        table.className = 'data-table';
        var thead = document.createElement('thead');
        thead.innerHTML = '<tr><th>Template</th><th>Source</th><th>Options</th><th>Target</th><th>Status</th><th>Created</th><th>Completed</th><th></th></tr>';
        table.appendChild(thead);

        var tbody = document.createElement('tbody');
        // Show newest first
        var sorted = jobs.slice().reverse();
        sorted.forEach(function (job) {
            var tr = document.createElement('tr');
            var snap = job.templateSnapshot || {};
            var meta = STATUS_META[job.status] || STATUS_META.created;

            var opts = [];
            if (snap.reorganize) opts.push('Reorganize');
            if (snap.removeDuplicates) opts.push('Remove Dups');
            if (snap.sendNotification) opts.push('Notify');

            var targetText = snap.target === 'toConnection'
                ? 'To: ' + esc(snap.connectionName || '—')
                : 'In Place';

            var sourceText = snap.source === 'fromConnection'
                ? esc(snap.sourceConnectionName || '—')
                : 'Default';

            var errors = job.errors || [];
            var hasErrors = job.status === 'error' && errors.length > 0;

            tr.innerHTML = '<td>' + esc(snap.name || '—') + '</td>'
                + '<td>' + sourceText + '</td>'
                + '<td>' + (opts.length ? esc(opts.join(', ')) : '<span style="color:#9e9e9e">None</span>') + '</td>'
                + '<td>' + esc(targetText) + '</td>'
                + '<td><span class="status-chip ' + meta.chip + '">'
                    + '<span class="material-icons" style="font-size:14px;vertical-align:middle;margin-right:2px;">' + meta.icon + '</span> '
                    + meta.label + '</span></td>'
                + '<td style="font-size:12px;color:var(--text-secondary);">' + fmtDate(job.createdAt) + '</td>'
                + '<td style="font-size:12px;color:var(--text-secondary);">' + fmtDate(job.completedAt) + '</td>'
                + '<td class="conn-actions"></td>';

            var actions = tr.querySelector('.conn-actions');

            if (hasErrors) {
                var errBtn = document.createElement('button');
                errBtn.className = 'icon-btn-sm';
                errBtn.title = 'View errors';
                errBtn.innerHTML = '<span class="material-icons">report_problem</span>';
                errBtn.style.color = '#c62828';
                errBtn.addEventListener('click', function () { showErrors(job); });
                actions.appendChild(errBtn);
            }

            var delBtn = document.createElement('button');
            delBtn.className = 'icon-btn-sm';
            delBtn.title = 'Delete';
            delBtn.innerHTML = '<span class="material-icons">delete</span>';
            delBtn.style.color = '#c62828';
            delBtn.addEventListener('click', function () { deleteJob(job.id); });
            actions.appendChild(delBtn);

            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        listEl.innerHTML = '';
        listEl.appendChild(table);
    }

    function showErrors(job) {
        var errors = job.errors || [];
        var snap = job.templateSnapshot || {};
        errorContent.innerHTML = '';

        var header = document.createElement('p');
        header.style.cssText = 'font-size:13px;color:var(--text-secondary);margin-bottom:12px;';
        header.textContent = 'Job "' + (snap.name || '—') + '" — ' + errors.length + ' error(s)';
        errorContent.appendChild(header);

        var list = document.createElement('ul');
        list.className = 'jobs-error-list';
        errors.forEach(function (msg) {
            var li = document.createElement('li');
            li.textContent = msg;
            list.appendChild(li);
        });
        errorContent.appendChild(list);
        errorBackdrop.style.display = '';
    }

    function closeErrorDialog() {
        errorBackdrop.style.display = 'none';
    }
    errorCloseBtn.addEventListener('click', closeErrorDialog);
    errorOkBtn.addEventListener('click', closeErrorDialog);
    errorBackdrop.addEventListener('click', function (e) { if (e.target === errorBackdrop) closeErrorDialog(); });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && errorBackdrop.style.display !== 'none') closeErrorDialog();
    });

    function deleteJob(id) {
        if (!confirm('Delete this job?')) return;
        fetch('/api/jobs/' + encodeURIComponent(id), { method: 'DELETE' })
            .then(function () { loadAll(); })
            .catch(function (err) { alert('Failed: ' + err.message); });
    }

    function fmtDate(iso) {
        if (!iso) return '—';
        try {
            var d = new Date(iso);
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString();
        } catch (e) { return iso; }
    }

    function esc(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    loadAll();
});
