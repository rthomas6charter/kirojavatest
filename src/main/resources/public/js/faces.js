document.addEventListener('DOMContentLoaded', function () {
    var wrap = document.getElementById('faces-table-wrap');
    var backdrop = document.getElementById('face-dialog-backdrop');
    var form = document.getElementById('face-edit-form');
    var closeBtn = document.getElementById('face-dialog-close');
    var saveBtn = document.getElementById('face-save-btn');
    var cancelBtn = document.getElementById('face-cancel-btn');
    if (!wrap) return;

    var editingIndex = -1;
    var facesData = [];

    loadFaces();

    function loadFaces() {
        fetch('/api/faces')
            .then(function (res) { return res.ok ? res.json() : []; })
            .then(function (data) {
                facesData = Array.isArray(data) ? data : [];
                renderTable();
            })
            .catch(function () {
                wrap.innerHTML = '<p class="empty-state">Failed to load faces data.</p>';
            });
    }

    function renderTable() {
        if (facesData.length === 0) {
            wrap.innerHTML = '<p class="empty-state">No known faces registered. Use the face service to register faces.</p>';
            return;
        }

        // Collect all keys across all entries (excluding embedding)
        var keys = [];
        var keySet = {};
        facesData.forEach(function (face) {
            Object.keys(face).forEach(function (k) {
                if (k !== 'embedding' && !keySet[k]) {
                    keySet[k] = true;
                    keys.push(k);
                }
            });
        });

        var table = document.createElement('table');
        table.className = 'data-table';
        table.setAttribute('aria-label', 'Known faces');

        // Header
        var thead = document.createElement('thead');
        var headerRow = document.createElement('tr');
        headerRow.innerHTML = '<th>#</th>';
        keys.forEach(function (k) {
            var th = document.createElement('th');
            th.textContent = k;
            headerRow.appendChild(th);
        });
        headerRow.innerHTML += '<th></th>';
        thead.appendChild(headerRow);
        table.appendChild(thead);

        // Body
        var tbody = document.createElement('tbody');
        facesData.forEach(function (face, idx) {
            var tr = document.createElement('tr');
            tr.innerHTML = '<td>' + idx + '</td>';
            keys.forEach(function (k) {
                var td = document.createElement('td');
                var val = face[k];
                if (val == null) {
                    td.textContent = '—';
                } else if (typeof val === 'object') {
                    td.textContent = JSON.stringify(val).substring(0, 40) + '…';
                    td.title = JSON.stringify(val);
                } else {
                    td.textContent = String(val);
                }
                tr.appendChild(td);
            });
            var actionTd = document.createElement('td');
            var editBtn = document.createElement('button');
            editBtn.className = 'icon-btn-sm';
            editBtn.title = 'Edit';
            editBtn.innerHTML = '<span class="material-icons">edit</span>';
            editBtn.addEventListener('click', function () { openEditDialog(idx, keys); });
            actionTd.appendChild(editBtn);
            tr.appendChild(actionTd);
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);

        wrap.innerHTML = '';
        wrap.appendChild(table);
    }

    function openEditDialog(index, keys) {
        editingIndex = index;
        var face = facesData[index];
        form.innerHTML = '';

        keys.forEach(function (k) {
            var val = face[k];
            var label = document.createElement('label');
            label.className = 'form-label';
            label.textContent = k;
            label.setAttribute('for', 'face-field-' + k);

            var input = document.createElement('input');
            input.className = 'form-input';
            input.id = 'face-field-' + k;
            input.name = k;
            if (val == null) {
                input.value = '';
            } else if (typeof val === 'object') {
                input.value = JSON.stringify(val);
                input.readOnly = true;
                input.style.color = '#9e9e9e';
            } else {
                input.value = String(val);
            }

            form.appendChild(label);
            form.appendChild(input);
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
        var inputs = form.querySelectorAll('input:not([readonly])');
        inputs.forEach(function (input) {
            updates[input.name] = input.value;
        });

        fetch('/api/faces/' + editingIndex, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(updates)
        })
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function () {
            closeDialog();
            loadFaces();
        })
        .catch(function (err) {
            alert('Failed to save: ' + err.message);
        });
    }

    closeBtn.addEventListener('click', closeDialog);
    cancelBtn.addEventListener('click', closeDialog);
    saveBtn.addEventListener('click', saveEdit);
    backdrop.addEventListener('click', function (e) {
        if (e.target === backdrop) closeDialog();
    });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && backdrop.style.display !== 'none') closeDialog();
    });
});
