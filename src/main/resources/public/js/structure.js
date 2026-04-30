document.addEventListener('DOMContentLoaded', function () {
    var container = document.getElementById('structure-tree');
    if (!container) return;

    var STORAGE_KEY = 'structureExpandedPaths';
    var SCROLL_KEY = 'structureScrollTop';
    var expandedPaths = new Set();

    // Load saved expanded state
    try {
        var saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
        if (Array.isArray(saved)) saved.forEach(function (p) { expandedPaths.add(p); });
    } catch (e) {}

    fetch('/api/structure')
        .then(function (res) { if (!res.ok) throw new Error('HTTP ' + res.status); return res.json(); })
        .then(function (files) {
            renderTree(files);
            restoreExpanded();
            restoreScroll();
        })
        .catch(function (err) {
            container.innerHTML = '<p class="empty-state">Failed to load: ' + escapeHtml(err.message) + '</p>';
        });

    // Save scroll position on scroll
    var wrapper = container.closest('.file-tree-wrapper');
    if (wrapper) {
        wrapper.addEventListener('scroll', debounce(function () {
            localStorage.setItem(SCROLL_KEY, String(wrapper.scrollTop));
        }, 300));
    }

    function saveExpandedState() {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(expandedPaths)));
    }

    function restoreExpanded() {
        var dirs = container.querySelectorAll('li.tree-dir');
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

    function restoreScroll() {
        if (!wrapper) return;
        var saved = localStorage.getItem(SCROLL_KEY);
        if (saved) {
            requestAnimationFrame(function () { wrapper.scrollTop = parseInt(saved, 10) || 0; });
        }
    }

    function renderTree(files) {
        container.innerHTML = '';
        if (files.length === 0) {
            container.innerHTML = '<p class="empty-state">No files found.</p>';
            return;
        }

        var root = { children: {}, _files: [], _path: '' };
        files.forEach(function (f) {
            var parts = f.virtualPath.split('/');
            var node = root;
            var pathSoFar = '';
            for (var i = 0; i < parts.length - 1; i++) {
                pathSoFar += (pathSoFar ? '/' : '') + parts[i];
                if (!node.children[parts[i]]) {
                    node.children[parts[i]] = { children: {}, _files: [], _path: pathSoFar };
                }
                node = node.children[parts[i]];
            }
            f._leafName = parts[parts.length - 1];
            node._files.push(f);
        });

        var ul = document.createElement('ul');
        renderNode(root, ul);
        container.appendChild(ul);
    }

    function renderNode(node, parentUl) {
        var dirNames = Object.keys(node.children).sort(function (a, b) {
            return a.localeCompare(b, undefined, { numeric: true });
        });
        var fileList = (node._files || []).slice().sort(function (a, b) {
            return a._leafName.localeCompare(b._leafName);
        });

        dirNames.forEach(function (name) {
            var child = node.children[name];
            var dirPath = child._path;
            var li = document.createElement('li');
            li.className = 'tree-dir';
            li.setAttribute('data-path', dirPath);

            var toggle = document.createElement('div');
            toggle.className = 'tree-toggle';
            toggle.setAttribute('role', 'button');
            toggle.setAttribute('tabindex', '0');
            toggle.setAttribute('aria-expanded', 'false');
            toggle.innerHTML = '<span class="material-icons tree-arrow">chevron_right</span>'
                + '<span class="material-icons tree-icon">folder</span>'
                + '<span class="tree-name">' + escapeHtml(name) + '</span>'
                + '<span class="tree-meta"></span>'
                + '<span class="tree-meta"></span>'
                + '<span class="tree-size"></span>'
                + '<span class="struct-dup-col"></span>'
                + '<span class="tree-action"></span>';

            var childUl = document.createElement('ul');
            childUl.className = 'tree-children';
            childUl.style.display = 'none';
            renderNode(child, childUl);

            toggle.addEventListener('click', function () {
                var expanded = toggle.getAttribute('aria-expanded') === 'true';
                childUl.style.display = expanded ? 'none' : '';
                toggle.setAttribute('aria-expanded', String(!expanded));
                toggle.querySelector('.tree-arrow').textContent = expanded ? 'chevron_right' : 'expand_more';
                toggle.querySelector('.tree-icon').textContent = expanded ? 'folder' : 'folder_open';
                if (expanded) {
                    expandedPaths.delete(dirPath);
                } else {
                    expandedPaths.add(dirPath);
                }
                saveExpandedState();
            });
            toggle.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle.click(); }
            });

            li.appendChild(toggle);
            li.appendChild(childUl);
            parentUl.appendChild(li);
        });

        fileList.forEach(function (f) {
            var li = document.createElement('li');
            var isMoved = f.moved;
            var isDup = f.dupCount > 1;
            li.className = 'tree-file' + (isMoved ? ' struct-moved' : '');

            var dupHtml = '';
            if (isDup) {
                var tooltip = f.sources.map(function (s) { return s.actualPath; }).join('\n');
                dupHtml = '<span class="struct-dup-badge" title="' + escapeAttr(tooltip) + '">' + f.dupCount + '</span>';
            }

            var movedIcon = isMoved
                ? '<span class="material-icons struct-moved-icon" title="Shown in reorganized path">swap_horiz</span>'
                : '';

            li.innerHTML = '<span class="tree-arrow"></span>'
                + '<span class="material-icons tree-icon">' + (isDup ? 'file_copy' : 'description') + '</span>'
                + '<span class="tree-name">' + escapeHtml(f._leafName) + ' ' + movedIcon + '</span>'
                + '<span class="tree-meta">' + formatDate(f.created) + '</span>'
                + '<span class="tree-meta">' + formatDate(f.modified) + '</span>'
                + '<span class="tree-size">' + formatSize(f.size) + '</span>'
                + '<span class="struct-dup-col">' + dupHtml + '</span>'
                + '<span class="tree-action"></span>';

            if (isDup || isMoved) {
                var paths = f.sources.map(function (s) { return s.actualPath; }).join('\n');
                li.title = 'Actual path(s):\n' + paths;
            }

            parentUl.appendChild(li);
        });
    }

    function debounce(fn, delay) {
        var timer;
        return function () {
            clearTimeout(timer);
            timer = setTimeout(fn, delay);
        };
    }

    function formatSize(bytes) {
        if (bytes == null) return '';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
        return (bytes / 1073741824).toFixed(1) + ' GB';
    }

    function formatDate(isoStr) {
        if (!isoStr) return '';
        try {
            var d = new Date(isoStr);
            return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (e) { return ''; }
    }

    function escapeHtml(str) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(str));
        return div.innerHTML;
    }

    function escapeAttr(str) {
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
    }
});
