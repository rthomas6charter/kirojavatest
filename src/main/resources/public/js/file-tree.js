document.addEventListener('DOMContentLoaded', function () {
    var container = document.getElementById('file-tree');
    if (!container) return;

    var POLL_INTERVAL = 5000;
    var expandedPaths = new Set();
    var duplicatePaths = new Set();
    var duplicateGroups = [];  // array of arrays of relative paths
    var rootPath = '';
    var stateLoaded = false;
    var saveTimer = null;
    var lastRootData = null;

    // Collapse all button
    var collapseBtn = document.getElementById('collapse-all-btn');
    if (collapseBtn) {
        collapseBtn.addEventListener('click', function () {
            expandedPaths.clear();
            scheduleSaveState();
            if (lastRootData) {
                renderRoot(lastRootData);
            }
        });
    }

    // Load saved expanded state and duplicates, then load the tree
    Promise.all([
        fetch('/api/files/state').then(function (res) { return res.ok ? res.json() : []; }),
        fetch('/api/files/duplicates').then(function (res) { return res.ok ? res.json() : { groups: [], paths: [] }; })
    ]).then(function (results) {
        if (Array.isArray(results[0])) {
            results[0].forEach(function (p) { expandedPaths.add(p); });
        }
        var dupData = results[1];
        if (dupData && Array.isArray(dupData.paths)) {
            dupData.paths.forEach(function (p) { duplicatePaths.add(p); });
        }
        if (dupData && Array.isArray(dupData.groups)) {
            duplicateGroups = dupData.groups;
        }
        stateLoaded = true;
        loadRoot();
    }).catch(function () {
        stateLoaded = true;
        loadRoot();
    });

    function loadRoot() {
        fetchLevel('').then(function (data) {
            renderRoot(data);
            restoreExpandedState();
        });
        setInterval(function () { pollRoot(); }, POLL_INTERVAL);
    }

    function fetchLevel(path) {
        var url = '/api/files' + (path ? '?path=' + encodeURIComponent(path) : '');
        return fetch(url)
            .then(function (res) {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.json();
            });
    }

    function renderRoot(data) {
        lastRootData = data;
        var wrapper = container.closest('.file-tree-wrapper');

        // Find a visible anchor element to stabilize scroll position
        var anchor = findVisibleAnchor(wrapper);
        var anchorPath = anchor ? anchor.el.getAttribute('data-path') : null;
        var anchorOffset = anchor ? anchor.offsetFromViewport : 0;

        container.innerHTML = '';
        var items = data.items || [];
        rootPath = data.path || '';
        if (items.length === 0) {
            container.innerHTML = '<p class="empty-state">No files found in the configured data directory.</p>';
            return;
        }
        var ul = document.createElement('ul');
        items.forEach(function (item) {
            ul.appendChild(buildNode(item));
        });
        container.appendChild(ul);

        // Restore scroll so the anchor element stays at the same screen position
        if (wrapper && anchorPath) {
            var restored = container.querySelector('[data-path="' + CSS.escape(anchorPath) + '"]');
            if (restored) {
                var newTop = restored.getBoundingClientRect().top;
                wrapper.scrollTop += (newTop - anchorOffset);
            }
        }
    }

    /** Find the first tree item (file or dir) that is visible in the wrapper viewport. */
    function findVisibleAnchor(wrapper) {
        if (!wrapper) return null;
        var wrapperRect = wrapper.getBoundingClientRect();
        var items = container.querySelectorAll('li[data-path]');
        for (var i = 0; i < items.length; i++) {
            var rect = items[i].getBoundingClientRect();
            // Item is at least partially visible in the wrapper
            if (rect.bottom > wrapperRect.top && rect.top < wrapperRect.bottom) {
                return { el: items[i], offsetFromViewport: rect.top };
            }
        }
        return null;
    }

    function buildNode(item) {
        if (item.type === 'file') {
            var li = document.createElement('li');
            var isDup = duplicatePaths.has(item.path);
            li.className = 'tree-file' + (isDup ? ' tree-file-dup' : '');
            li.setAttribute('data-path', item.path);
            var fileIcon = isDup ? 'file_copy' : 'description';
            var suggestedHtml = item.suggestedPath
                ? '<span class="tree-suggested" title="' + escapeAttr(item.suggestedPath) + '">'
                    + '<span class="material-icons tree-suggested-icon">subdirectory_arrow_right</span>'
                    + escapeHtml(suggestedDir(item.suggestedPath)) + '</span>'
                : '<span class="tree-suggested"></span>';
            var alertsHtml = '<span class="tree-alerts">';
            if (item.extMismatch) {
                alertsHtml += '<span class="alert-badge alert-fn" title="File name extension / content mismatch">fn</span>';
            }
            alertsHtml += '</span>';
            li.innerHTML = '<span class="tree-arrow"></span>'
                + '<span class="material-icons tree-icon">' + fileIcon + '</span>'
                + '<span class="tree-name">' + escapeHtml(item.name) + '</span>'
                + '<span class="tree-meta">' + formatDate(item.created) + '</span>'
                + '<span class="tree-meta">' + formatDate(item.modified) + '</span>'
                + '<span class="tree-size">' + formatSize(item.size) + '</span>'
                + suggestedHtml
                + alertsHtml
                + '<span class="tree-action">'
                + (isDup ? '<button class="icon-btn-sm dup-inspect" title="Show duplicates" data-path="' + escapeAttr(item.path) + '">'
                    + '<span class="material-icons">search</span></button>' : '')
                + '</span>';

            if (isDup) {
                li.querySelector('.dup-inspect').addEventListener('click', function (e) {
                    e.stopPropagation();
                    showDuplicateOverlay(item.path, this);
                });
            }
            return li;
        }

        // Directory node
        var li = document.createElement('li');
        li.className = 'tree-dir';
        li.setAttribute('data-path', item.path);
        var isEmpty = !item.hasChildren;

        var toggle = document.createElement('div');
        toggle.className = 'tree-toggle' + (isEmpty ? ' tree-toggle-empty' : '');
        toggle.setAttribute('role', 'button');
        toggle.setAttribute('tabindex', isEmpty ? '-1' : '0');
        toggle.setAttribute('aria-expanded', 'false');

        var emptyLabel = isEmpty ? '<span class="tree-empty-label">(empty)</span>' : '';

        if (isEmpty) {
            toggle.innerHTML = '<span class="tree-arrow"></span>'
                + '<span class="material-icons tree-icon tree-icon-disabled">folder</span>'
                + '<span class="tree-name">' + escapeHtml(item.name) + '</span>'
                + '<span class="tree-meta"></span>'
                + '<span class="tree-meta"></span>'
                + '<span class="tree-size tree-empty-label">(empty)</span>'
                + '<span class="tree-suggested"></span>'
                + '<span class="tree-alerts"></span>'
                + '<span class="tree-action"></span>';
            li.appendChild(toggle);
            return li;
        }

        toggle.innerHTML = '<span class="material-icons tree-arrow">chevron_right</span>'
            + '<span class="material-icons tree-icon">folder</span>'
            + '<span class="tree-name">' + escapeHtml(item.name) + '</span>'
            + '<span class="tree-meta"></span>'
            + '<span class="tree-meta"></span>'
            + '<span class="tree-size">' + formatSize(item.size) + '</span>'
            + '<span class="tree-suggested"></span>'
            + '<span class="tree-alerts"></span>'
            + '<span class="tree-action"></span>';

        var childList = document.createElement('ul');
        childList.className = 'tree-children';
        childList.style.display = 'none';

        var loaded = false;

        toggle.addEventListener('click', function () {
            var isExpanded = toggle.getAttribute('aria-expanded') === 'true';
            if (isExpanded) {
                childList.style.display = 'none';
                toggle.setAttribute('aria-expanded', 'false');
                toggle.querySelector('.tree-arrow').textContent = 'chevron_right';
                toggle.querySelector('.tree-icon').textContent = 'folder';
                expandedPaths.delete(item.path);
                scheduleSaveState();
            } else {
                if (!loaded) {
                    childList.innerHTML = '<li class="tree-empty">Loading...</li>';
                    childList.style.display = '';
                    fetchLevel(item.path).then(function (data) {
                        loaded = true;
                        childList.innerHTML = '';
                        var kids = data.items || [];
                        kids.forEach(function (child) {
                            childList.appendChild(buildNode(child));
                        });
                        restoreExpandedIn(childList);
                    });
                } else {
                    childList.style.display = '';
                }
                toggle.setAttribute('aria-expanded', 'true');
                toggle.querySelector('.tree-arrow').textContent = 'expand_more';
                toggle.querySelector('.tree-icon').textContent = 'folder_open';
                expandedPaths.add(item.path);
                scheduleSaveState();
            }
        });

        toggle.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle.click(); }
        });

        li.appendChild(toggle);
        li.appendChild(childList);
        return li;
    }

    // --- Duplicate overlay ---

    function findGroupForPath(filePath) {
        for (var i = 0; i < duplicateGroups.length; i++) {
            var group = duplicateGroups[i];
            for (var j = 0; j < group.length; j++) {
                if (group[j].path === filePath) return group;
            }
        }
        return [];
    }

    function showDuplicateOverlay(filePath, buttonEl) {
        closeOverlay();
        var group = findGroupForPath(filePath);
        if (group.length === 0) return;

        // Find the clicked file's entry for comparison
        var sourceEntry = null;
        var others = [];
        group.forEach(function (entry) {
            if (entry.path === filePath) {
                sourceEntry = entry;
            } else {
                others.push(entry);
            }
        });
        if (!sourceEntry || others.length === 0) return;

        var backdrop = document.createElement('div');
        backdrop.className = 'dup-overlay-backdrop';
        backdrop.id = 'dup-overlay-backdrop';

        var panel = document.createElement('div');
        panel.className = 'dup-overlay-panel';

        // Close button
        var closeBtn = document.createElement('button');
        closeBtn.className = 'dup-overlay-close';
        closeBtn.setAttribute('aria-label', 'Close');
        closeBtn.innerHTML = '<span class="material-icons">close</span>';
        closeBtn.addEventListener('click', function () { closeOverlay(); });
        panel.appendChild(closeBtn);

        var header = document.createElement('div');
        header.className = 'dup-overlay-header';
        header.innerHTML = '<span class="material-icons">file_copy</span> '
            + '<strong>' + others.length + ' identical file' + (others.length > 1 ? 's' : '') + '</strong>';
        panel.appendChild(header);

        // Column headers
        var colHeader = document.createElement('div');
        colHeader.className = 'dup-row dup-row-header';
        colHeader.innerHTML = '<span class="dup-col-icon"></span>'
            + '<span class="dup-col-path">Path</span>'
            + '<span class="dup-col-delta">Created \u0394</span>'
            + '<span class="dup-col-delta">Modified \u0394</span>';
        panel.appendChild(colHeader);

        var list = document.createElement('div');
        list.className = 'dup-overlay-list';
        others.forEach(function (entry) {
            var row = document.createElement('div');
            row.className = 'dup-row';

            var sameFilename = getFilename(entry.path) === getFilename(filePath);
            var nameIcon = sameFilename ? 'drag_handle' : 'code'; // ≈ equal vs not-equal

            var createdDelta = formatTimeDelta(sourceEntry.created, entry.created);
            var modifiedDelta = formatTimeDelta(sourceEntry.modified, entry.modified);

            row.innerHTML = '<span class="dup-col-icon"><span class="material-icons dup-name-icon'
                + (sameFilename ? ' dup-name-eq' : ' dup-name-neq') + '" title="'
                + (sameFilename ? 'Same filename' : 'Different filename') + '">'
                + nameIcon + '</span></span>'
                + '<a href="#" class="dup-col-path dup-overlay-link">' + escapeHtml(entry.path) + '</a>'
                + '<span class="dup-col-delta">' + createdDelta + '</span>'
                + '<span class="dup-col-delta">' + modifiedDelta + '</span>';

            row.querySelector('.dup-overlay-link').addEventListener('click', function (e) {
                e.preventDefault();
                closeOverlay();
                navigateToFile(entry.path);
            });

            list.appendChild(row);
        });
        panel.appendChild(list);
        backdrop.appendChild(panel);
        document.body.appendChild(backdrop);

        // Position panel to the left of the button
        var btnRect = buttonEl.getBoundingClientRect();
        var panelRect = panel.getBoundingClientRect();
        var top = btnRect.top + (btnRect.height / 2) - (panelRect.height / 2);
        var left = btnRect.left - panelRect.width - 12;

        if (top < 8) top = 8;
        if (top + panelRect.height > window.innerHeight - 8) top = window.innerHeight - panelRect.height - 8;
        if (left < 8) left = btnRect.right + 12;

        panel.style.top = top + 'px';
        panel.style.left = left + 'px';

        backdrop.addEventListener('click', function (e) {
            if (e.target === backdrop) closeOverlay();
        });
        document.addEventListener('keydown', overlayKeyHandler);
    }

    function overlayKeyHandler(e) {
        if (e.key === 'Escape') closeOverlay();
    }

    function closeOverlay() {
        var existing = document.getElementById('dup-overlay-backdrop');
        if (existing) existing.remove();
        document.removeEventListener('keydown', overlayKeyHandler);
    }

    function navigateToFile(filePath) {
        // Split path into segments: expand each parent folder, then scroll to the file
        var segments = filePath.split('/');
        var parentPaths = [];
        for (var i = 0; i < segments.length - 1; i++) {
            parentPaths.push(segments.slice(0, i + 1).join('/'));
        }

        // Sequentially expand each parent
        expandSequentially(parentPaths, 0, function () {
            // After all parents expanded, find and scroll to the file
            setTimeout(function () {
                var target = container.querySelector('li[data-path="' + CSS.escape(filePath) + '"]');
                if (target) {
                    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    target.classList.add('tree-highlight');
                    setTimeout(function () { target.classList.remove('tree-highlight'); }, 2000);
                }
            }, 200);
        });
    }

    function expandSequentially(paths, index, callback) {
        if (index >= paths.length) { callback(); return; }
        var dirLi = container.querySelector('li.tree-dir[data-path="' + CSS.escape(paths[index]) + '"]');
        if (dirLi) {
            var toggle = dirLi.querySelector(':scope > .tree-toggle');
            if (toggle && toggle.getAttribute('aria-expanded') !== 'true') {
                toggle.click();
                // Wait for lazy load to complete
                setTimeout(function () { expandSequentially(paths, index + 1, callback); }, 300);
                return;
            }
        }
        expandSequentially(paths, index + 1, callback);
    }

    // --- State persistence ---

    function scheduleSaveState() {
        if (saveTimer) clearTimeout(saveTimer);
        saveTimer = setTimeout(saveState, 500);
    }

    function saveState() {
        var paths = Array.from(expandedPaths);
        fetch('/api/files/state', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(paths)
        }).catch(function () {});
    }

    function restoreExpandedState() {
        restoreExpandedIn(container);
    }

    function restoreExpandedIn(parent) {
        var dirs = parent.querySelectorAll('li.tree-dir');
        dirs.forEach(function (li) {
            var path = li.getAttribute('data-path');
            if (path && expandedPaths.has(path)) {
                var toggle = li.querySelector(':scope > .tree-toggle');
                if (toggle && toggle.getAttribute('aria-expanded') !== 'true') {
                    toggle.click();
                }
            }
        });
    }

    // --- Polling ---

    var lastRootJson = '';
    var lastDupJson = '';

    function pollRoot() {
        Promise.all([
            fetchLevel(''),
            refreshDuplicates()
        ]).then(function (results) {
            var data = results[0];
            var json = JSON.stringify(data.items || []);
            var changed = (json !== lastRootJson) || duplicatesChanged;
            lastRootJson = json;
            duplicatesChanged = false;
            if (changed) {
                renderRoot(data);
                restoreExpandedState();
            }
        }).catch(function () {});
    }

    var duplicatesChanged = false;

    function refreshDuplicates() {
        return fetch('/api/files/duplicates')
            .then(function (res) { return res.ok ? res.json() : { groups: [], paths: [] }; })
            .then(function (dupData) {
                var newJson = JSON.stringify(dupData.paths || []);
                if (newJson !== lastDupJson) {
                    lastDupJson = newJson;
                    duplicatesChanged = true;
                    duplicatePaths.clear();
                    duplicateGroups = [];
                    if (dupData && Array.isArray(dupData.paths)) {
                        dupData.paths.forEach(function (p) { duplicatePaths.add(p); });
                    }
                    if (dupData && Array.isArray(dupData.groups)) {
                        duplicateGroups = dupData.groups;
                    }
                }
            })
            .catch(function () {});
    }

    // --- Utilities ---

    function formatSize(bytes) {
        if (bytes == null) return '<span class="size-num"></span><span class="size-unit"></span>';
        var num, unit;
        if (bytes < 1024) { num = bytes; unit = 'B'; }
        else if (bytes < 1048576) { num = (bytes / 1024).toFixed(1); unit = 'KB'; }
        else if (bytes < 1073741824) { num = (bytes / 1048576).toFixed(1); unit = 'MB'; }
        else { num = (bytes / 1073741824).toFixed(1); unit = 'GB'; }
        return '<span class="size-num">' + num + '</span><span class="size-unit">' + unit + '</span>';
    }

    function formatDate(isoStr) {
        if (!isoStr) return '—';
        try {
            var d = new Date(isoStr);
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (e) { return '—'; }
    }

    function escapeHtml(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    function escapeAttr(str) {
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
    }

    function getFilename(path) {
        var parts = path.split('/');
        return parts[parts.length - 1];
    }

    function suggestedDir(path) {
        var idx = path.lastIndexOf('/');
        if (idx < 0) return '\u2026';
        return path.substring(0, idx) + '/\u2026';
    }

    function formatTimeDelta(isoA, isoB) {
        if (!isoA || !isoB) return '—';
        try {
            var a = new Date(isoA).getTime();
            var b = new Date(isoB).getTime();
            var diffMs = b - a;
            var sign = diffMs >= 0 ? '+' : '-';
            diffMs = Math.abs(diffMs);
            if (diffMs < 1000) return '0s';
            var secs = Math.floor(diffMs / 1000);
            if (secs < 60) return sign + secs + 's';
            var mins = Math.floor(secs / 60);
            if (mins < 60) return sign + mins + 'm';
            var hrs = Math.floor(mins / 60);
            if (hrs < 24) return sign + hrs + 'h ' + (mins % 60) + 'm';
            var days = Math.floor(hrs / 24);
            return sign + days + 'd ' + (hrs % 24) + 'h';
        } catch (e) { return '—'; }
    }
});
