document.addEventListener('DOMContentLoaded', function () {
    var panel = document.getElementById('summary-panel');
    if (!panel) return;

    loadSummary();
    setInterval(loadSummary, 10000);

    function loadSummary() {
        fetch('/api/summary')
            .then(function (res) { return res.ok ? res.json() : {}; })
            .then(function (data) {
                setText('stat-total-files', data.totalFiles != null ? data.totalFiles.toLocaleString() : '—');
                setText('stat-reorg-count', data.needsReorgCount != null ? data.needsReorgCount.toLocaleString() : '—');
                setText('stat-dup-groups', data.dupGroupCount != null ? data.dupGroupCount.toLocaleString() : '—');
                setText('stat-after-dedup', data.filesAfterDedup != null ? data.filesAfterDedup.toLocaleString() : '—');
                setText('stat-reclaimable', data.reclaimableBytes != null ? formatBytes(data.reclaimableBytes) : '—');
            })
            .catch(function () {});
    }

    function setText(id, val) {
        var el = document.getElementById(id);
        if (el) el.textContent = val;
    }

    function formatBytes(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB';
        return (bytes / 1073741824).toFixed(1) + ' GB';
    }
});
