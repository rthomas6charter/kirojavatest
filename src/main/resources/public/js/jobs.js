document.addEventListener('DOMContentLoaded', function () {
    var listEl = document.getElementById('jobs-list');
    var tplSelect = document.getElementById('jobs-create-tpl');
    var createBtn = document.getElementById('jobs-create-btn');
    var refreshBtn = document.getElementById('jobs-refresh-btn');
    var errorBackdrop = document.getElementById('jobs-error-backdrop');
    var errorContent = document.getElementById('jobs-error-content');
    var errorCloseBtn = document.getElementById('jobs-error-close');
    var errorOkBtn = document.getElementById('jobs-error-ok-btn');
    var overrideBackdrop = document.getElementById('jobs-override-backdrop');
    var overrideCloseBtn = document.getElementById('jobs-override-close');
    var overrideCancelBtn = document.getElementById('jobs-override-cancel-btn');
    var overrideSaveBtn = document.getElementById('jobs-override-save-btn');
    var overrideSrcSelect = document.getElementById('jobs-override-source');
    var overrideTgtSelect = document.getElementById('jobs-override-target');
    var autoRunCb = document.getElementById('jobs-auto-run');
    var maxConcurrentSelect = document.getElementById('jobs-max-concurrent');
    if (!listEl) return;

    var jobs = [];
    var templates = [];
    var connections = [];
    var overrideJobId = null;

    refreshBtn.addEventListener('click', function () { loadAll(); });
    createBtn.addEventListener('click', function () { createJob(); });

    // --- Job queue settings ---

    function loadSettings() {
        fetch('/api/settings').then(function (r) { return r.ok ? r.json() : {}; })
        .then(function (s) {
            autoRunCb.checked = !!s.autoRunNextJob;
            maxConcurrentSelect.value = String(s.maxConcurrentJobs || 1);
        }).catch(function () {});
    }

    function saveSetting(key, value) {
        var payload = {};
        payload[key] = value;
        fetch('/api/settings', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        }).catch(function () {});
    }

    autoRunCb.addEventListener('change', function () {
        saveSetting('autoRunNextJob', autoRunCb.checked);
        if (autoRunCb.checked) maybeAutoStart();
    });
    maxConcurrentSelect.addEventListener('change', function () {
        saveSetting('maxConcurrentJobs', parseInt(maxConcurrentSelect.value, 10));
        if (autoRunCb.checked) maybeAutoStart();
    });

    function maybeAutoStart() {
        if (!autoRunCb.checked) return;
        var maxConcurrent = parseInt(maxConcurrentSelect.value, 10) || 1;
        var runningCount = jobs.filter(function (j) { return j.status === 'running'; }).length;
        if (runningCount >= maxConcurrent) return;

        // Find the oldest "created" job (jobs array is in creation order)
        var slotsAvailable = maxConcurrent - runningCount;
        var created = jobs.filter(function (j) { return j.status === 'created'; });
        for (var i = 0; i < Math.min(slotsAvailable, created.length); i++) {
            startJob(created[i].id, true);
        }
    }

    function loadAll() {
        Promise.all([
            fetch('/api/jobs').then(function (r) { return r.ok ? r.json() : []; }),
            fetch('/api/job-templates').then(function (r) { return r.ok ? r.json() : []; }),
            fetch('/api/connections').then(function (r) { return r.ok ? r.json() : []; })
        ]).then(function (results) {
            jobs = Array.isArray(results[0]) ? results[0] : [];
            templates = Array.isArray(results[1]) ? results[1] : [];
            connections = Array.isArray(results[2]) ? results[2] : [];
            renderTemplateSelect();
            renderList();
            maybeAutoStart();
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

    function findConnByName(name) {
        for (var i = 0; i < connections.length; i++) {
            if (connections[i].name === name) return connections[i];
        }
        return null;
    }

    function connLabel(name) {
        var c = findConnByName(name);
        if (!c) return esc(name || '—');
        return esc(c.name) + ' (' + (c.type || 'smb').toUpperCase() + ')';
    }

    function getEffectiveSource(job) {
        if (job.sourceOverride === 'fromConnection' && job.sourceConnectionName)
            return connLabel(job.sourceConnectionName);
        if (job.sourceOverride === 'defaultDataDir' || job.sourceOverride === '') return 'Default';
        var snap = job.templateSnapshot || {};
        if (snap.source === 'fromConnection' && snap.sourceConnectionName)
            return connLabel(snap.sourceConnectionName);
        return 'Default';
    }

    function getEffectiveTarget(job) {
        if (job.targetOverride === 'toConnection' && job.targetConnectionName)
            return connLabel(job.targetConnectionName);
        if (job.targetOverride === 'inPlace' || job.targetOverride === '') return 'In Place';
        var snap = job.templateSnapshot || {};
        if (snap.target === 'toConnection' && snap.connectionName)
            return connLabel(snap.connectionName);
        return 'In Place';
    }

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
        var sorted = jobs.slice().reverse();
        sorted.forEach(function (job) {
            var tr = document.createElement('tr');
            var snap = job.templateSnapshot || {};
            var meta = STATUS_META[job.status] || STATUS_META.created;

            var opts = [];
            if (snap.reorganize) opts.push('Reorganize');
            if (snap.removeDuplicates) opts.push('Remove Dups');
            if (snap.sendNotification) opts.push('Notify');

            var sourceText = getEffectiveSource(job);
            var targetText = getEffectiveTarget(job);

            var errors = job.errors || [];
            var hasErrors = job.status === 'error' && errors.length > 0;

            tr.innerHTML = '<td>' + esc(snap.name || '—') + '</td>'
                + '<td style="max-width:150px;word-wrap:break-word;overflow-wrap:break-word;">' + sourceText + '</td>'
                + '<td>' + (opts.length ? esc(opts.join(', ')) : '<span style="color:#9e9e9e">None</span>') + '</td>'
                + '<td style="max-width:150px;word-wrap:break-word;overflow-wrap:break-word;">' + targetText + '</td>'
                + '<td><span class="status-chip ' + meta.chip + '">'
                    + '<span class="material-icons" style="font-size:14px;vertical-align:middle;margin-right:2px;">' + meta.icon + '</span> '
                    + meta.label + '</span></td>'
                + '<td style="font-size:12px;color:var(--text-secondary);">' + fmtDate(job.createdAt) + '</td>'
                + '<td style="font-size:12px;color:var(--text-secondary);">' + fmtDate(job.completedAt) + '</td>'
                + '<td class="conn-actions"></td>';

            var actions = tr.querySelector('.conn-actions');

            // Start button — only for "created" jobs
            if (job.status === 'created') {
                var startBtn = document.createElement('button');
                startBtn.className = 'icon-btn-sm';
                startBtn.title = 'Start job';
                startBtn.innerHTML = '<span class="material-icons">play_arrow</span>';
                startBtn.style.color = '#2e7d32';
                startBtn.addEventListener('click', function () { startJob(job.id); });
                actions.appendChild(startBtn);
            }

            // Cancel button — only for "running" jobs
            if (job.status === 'running') {
                var cancelJobBtn = document.createElement('button');
                cancelJobBtn.className = 'icon-btn-sm';
                cancelJobBtn.title = 'Cancel job';
                cancelJobBtn.innerHTML = '<span class="material-icons">cancel</span>';
                cancelJobBtn.style.color = '#e65100';
                cancelJobBtn.addEventListener('click', function () { cancelJob(job.id); });
                actions.appendChild(cancelJobBtn);
            }

            // Override source/target — only for "created" jobs
            if (job.status === 'created') {
                var overrideBtn = document.createElement('button');
                overrideBtn.className = 'icon-btn-sm';
                overrideBtn.title = 'Override source/target';
                overrideBtn.innerHTML = '<span class="material-icons">tune</span>';
                overrideBtn.style.color = '#5d4037';
                overrideBtn.addEventListener('click', function () { openOverrideDialog(job); });
                actions.appendChild(overrideBtn);
            }

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

    // --- Start / Cancel job ---

    function startJob(id, skipConfirm) {
        if (!skipConfirm && !confirm('Start this job? It may take a while to complete.')) return;
        fetch('/api/jobs/' + encodeURIComponent(id), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ status: 'running', startedAt: new Date().toISOString() })
        })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function () { loadAll(); })
        .catch(function (err) { alert('Failed to start job: ' + err.message); });
    }

    function cancelJob(id) {
        if (!confirm('Cancel this running job?')) return;
        fetch('/api/jobs/' + encodeURIComponent(id), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                status: 'error',
                completedAt: new Date().toISOString(),
                errors: ['Cancelled by user']
            })
        })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function () { loadAll(); })
        .catch(function (err) { alert('Failed to cancel job: ' + err.message); });
    }

    // --- Override source/target dialog ---

    function buildCombinedDropdown(selectEl, currentValue, defaultLabel) {
        selectEl.innerHTML = '';
        var defOpt = document.createElement('option');
        defOpt.value = '';
        defOpt.textContent = defaultLabel;
        selectEl.appendChild(defOpt);

        var named = connections.filter(function (c) { return c.name; });
        named.forEach(function (c) {
            var opt = document.createElement('option');
            opt.value = c.name;
            opt.textContent = c.name + ' (' + (c.type || 'smb').toUpperCase() + ')';
            selectEl.appendChild(opt);
        });

        selectEl.value = currentValue || '';
    }

    function openOverrideDialog(job) {
        overrideJobId = job.id;
        var snap = job.templateSnapshot || {};

        // Determine current effective source connection name
        var curSrcConn = '';
        if (job.sourceOverride === 'fromConnection') {
            curSrcConn = job.sourceConnectionName || '';
        } else if (job.sourceOverride === 'defaultDataDir' || job.sourceOverride === '') {
            curSrcConn = '';
        } else if (!job.sourceOverride && snap.source === 'fromConnection') {
            curSrcConn = snap.sourceConnectionName || '';
        }

        // Determine current effective target connection name
        var curTgtConn = '';
        if (job.targetOverride === 'toConnection') {
            curTgtConn = job.targetConnectionName || '';
        } else if (job.targetOverride === 'inPlace' || job.targetOverride === '') {
            curTgtConn = '';
        } else if (!job.targetOverride && snap.target === 'toConnection') {
            curTgtConn = snap.connectionName || '';
        }

        buildCombinedDropdown(overrideSrcSelect, curSrcConn, 'Default Data Directory');
        buildCombinedDropdown(overrideTgtSelect, curTgtConn, 'In Place');

        overrideBackdrop.style.display = '';
    }

    function closeOverrideDialog() {
        overrideBackdrop.style.display = 'none';
        overrideJobId = null;
    }
    if (overrideCloseBtn) overrideCloseBtn.addEventListener('click', closeOverrideDialog);
    if (overrideCancelBtn) overrideCancelBtn.addEventListener('click', closeOverrideDialog);
    if (overrideBackdrop) overrideBackdrop.addEventListener('click', function (e) {
        if (e.target === overrideBackdrop) closeOverrideDialog();
    });

    if (overrideSaveBtn) overrideSaveBtn.addEventListener('click', function () {
        if (!overrideJobId) return;
        var srcVal = overrideSrcSelect.value;
        var tgtVal = overrideTgtSelect.value;
        var payload = {
            sourceOverride: srcVal ? 'fromConnection' : 'defaultDataDir',
            sourceConnectionName: srcVal || '',
            targetOverride: tgtVal ? 'toConnection' : 'inPlace',
            targetConnectionName: tgtVal || ''
        };
        fetch('/api/jobs/' + encodeURIComponent(overrideJobId), {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function () { closeOverrideDialog(); loadAll(); })
        .catch(function (err) { alert('Failed to save overrides: ' + err.message); });
    });

    // --- Error dialog ---

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
        if (e.key === 'Escape') {
            if (errorBackdrop.style.display !== 'none') closeErrorDialog();
            if (overrideBackdrop && overrideBackdrop.style.display !== 'none') closeOverrideDialog();
        }
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
    loadSettings();
});
