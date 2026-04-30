document.addEventListener('DOMContentLoaded', function () {
    var tabs = document.querySelectorAll('.admin-tab');
    var panels = document.querySelectorAll('.admin-panel');

    tabs.forEach(function (tab) {
        tab.addEventListener('click', function () {
            var target = tab.getAttribute('data-tab');

            tabs.forEach(function (t) { t.classList.remove('active'); });
            tab.classList.add('active');

            panels.forEach(function (p) {
                p.style.display = p.id === 'tab-' + target ? '' : 'none';
            });

            // Remember selected tab
            localStorage.setItem('adminTab', target);
        });
    });

    // Restore last selected tab
    var saved = localStorage.getItem('adminTab');
    if (saved) {
        var savedTab = document.querySelector('.admin-tab[data-tab="' + saved + '"]');
        if (savedTab) savedTab.click();
    }

    // --- Theme selector ---
    var themeSelect = document.getElementById('theme-select');
    var themeStatus = document.getElementById('theme-status');
    if (themeSelect) {
        // Load current setting
        var currentTheme = localStorage.getItem('theme') || 'light';
        themeSelect.value = currentTheme;

        fetch('/api/settings')
            .then(function (res) { return res.ok ? res.json() : {}; })
            .then(function (settings) {
                if (settings.theme) {
                    themeSelect.value = settings.theme;
                    applyTheme(settings.theme);
                }
            })
            .catch(function () {});

        themeSelect.addEventListener('change', function () {
            var theme = themeSelect.value;
            applyTheme(theme);
            localStorage.setItem('theme', theme);
            fetch('/api/settings', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ theme: theme })
            })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                if (themeStatus) themeStatus.textContent = 'Saved.';
                setTimeout(function () { if (themeStatus) themeStatus.textContent = ''; }, 2000);
            })
            .catch(function (err) {
                if (themeStatus) themeStatus.textContent = 'Failed to save: ' + err.message;
            });
        });
    }

    // --- Directory structure type ---
    var dirSelect = document.getElementById('dir-structure-type');
    var dirStatus = document.getElementById('dir-structure-status');
    if (dirSelect) {
        // Load current setting
        fetch('/api/settings')
            .then(function (res) { return res.ok ? res.json() : {}; })
            .then(function (settings) {
                if (settings.directoryStructure) dirSelect.value = settings.directoryStructure;
            })
            .catch(function () {});

        dirSelect.addEventListener('change', function () {
            fetch('/api/settings', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ directoryStructure: dirSelect.value })
            })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                if (dirStatus) dirStatus.textContent = 'Saved.';
                setTimeout(function () { if (dirStatus) dirStatus.textContent = ''; }, 2000);
            })
            .catch(function (err) {
                if (dirStatus) dirStatus.textContent = 'Failed to save: ' + err.message;
            });
        });
    }

    // --- Optimizations ---
    var optTimeout = document.getElementById('opt-task-timeout');
    var optThreshold = document.getElementById('opt-queue-threshold');
    var optStatus = document.getElementById('opt-status');
    if (optTimeout && optThreshold) {
        fetch('/api/settings')
            .then(function (res) { return res.ok ? res.json() : {}; })
            .then(function (settings) {
                optTimeout.value = settings.backgroundTaskTimeout || 300;
                optThreshold.value = settings.backgroundQueueThreshold || 10;
            })
            .catch(function () {});

        function saveOpt() {
            var updates = {
                backgroundTaskTimeout: parseInt(optTimeout.value, 10) || 300,
                backgroundQueueThreshold: parseInt(optThreshold.value, 10) || 10
            };
            fetch('/api/settings', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(updates)
            })
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                if (optStatus) optStatus.textContent = 'Saved.';
                setTimeout(function () { if (optStatus) optStatus.textContent = ''; }, 2000);
            })
            .catch(function (err) {
                if (optStatus) optStatus.textContent = 'Failed: ' + err.message;
            });
        }
        optTimeout.addEventListener('change', saveOpt);
        optThreshold.addEventListener('change', saveOpt);
    }

    // --- Database stats ---
    var dbStatsEl = document.getElementById('db-stats');
    if (dbStatsEl) {
        loadDbStats();
    }

    function loadDbStats() {
        fetch('/api/db/stats')
            .then(function (res) { return res.ok ? res.json() : {}; })
            .then(function (stats) {
                dbStatsEl.innerHTML = '';
                var items = [
                    { label: 'Database Path', value: stats.path || '—' },
                    { label: 'Size on Disk', value: fmtBytes(stats.sizeBytes) },
                    { label: 'SQLite Version', value: stats.sqliteVersion || '—' },
                    { label: 'Journal Mode', value: stats.journalMode || '—' },
                    { label: 'Page Size', value: stats.pageSize ? stats.pageSize.toLocaleString() + ' bytes' : '—' },
                    { label: 'Page Count', value: stats.pageCount ? stats.pageCount.toLocaleString() : '—' },
                    { label: 'Tracked Files', value: stats.fileCount != null ? stats.fileCount.toLocaleString() : '—' },
                    { label: 'Total Tracked Size', value: fmtBytes(stats.totalTrackedBytes) },
                    { label: 'Duplicate Pairs', value: stats.duplicatePairCount != null ? stats.duplicatePairCount.toLocaleString() : '—' },
                    { label: 'Duplicate Groups', value: stats.duplicateGroupCount != null ? stats.duplicateGroupCount.toLocaleString() : '—' },
                    { label: 'Files Needing Reorg', value: stats.needsReorgCount != null ? stats.needsReorgCount.toLocaleString() : '—' }
                ];
                var grid = document.createElement('div');
                grid.className = 'db-info-grid';
                items.forEach(function (item) {
                    var row = document.createElement('div');
                    row.className = 'db-info-row';
                    row.innerHTML = '<span class="db-info-label">' + item.label + '</span>'
                        + '<span class="db-info-value">' + item.value + '</span>';
                    grid.appendChild(row);
                });
                dbStatsEl.appendChild(grid);
            })
            .catch(function () {
                dbStatsEl.innerHTML = '<p class="empty-state">Failed to load database stats.</p>';
            });
    }

    function fmtBytes(bytes) {
        if (bytes == null) return '—';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
        return (bytes / 1073741824).toFixed(1) + ' GB';
    }
});
