document.addEventListener('DOMContentLoaded', function () {
    var sidebar = document.getElementById('sidebar');
    var toggle = document.getElementById('menu-toggle');
    var isMobile = window.matchMedia('(max-width: 768px)');

    toggle.addEventListener('click', function () {
        if (isMobile.matches) {
            sidebar.classList.toggle('expanded');
        } else {
            sidebar.classList.toggle('collapsed');
        }
    });
});
