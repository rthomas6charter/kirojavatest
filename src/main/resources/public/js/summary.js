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
                setText('stat-reorg-time', data.estimatedReorgTimeSeconds != null ? formatDuration(data.estimatedReorgTimeSeconds) : '—');
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

    function formatDuration(seconds) {
        if (seconds < 1) return '< 1s';
        var totalSeconds = Math.round(seconds);
        var h = Math.floor(totalSeconds / 3600);
        var m = Math.floor((totalSeconds % 3600) / 60);
        var s = totalSeconds % 60;
        if (h > 0 && m > 0) return h + 'h ' + m + 'm';
        if (h > 0) return h + 'h';
        if (m > 0 && s > 0) return m + 'm ' + s + 's';
        if (m > 0) return m + 'm';
        return s + 's';
    }
});
