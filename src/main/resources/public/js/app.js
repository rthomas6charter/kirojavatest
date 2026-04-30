// Apply theme immediately (before DOMContentLoaded to avoid flash)
(function () {
    var theme = localStorage.getItem('theme') || 'light';
    applyTheme(theme);
})();

function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('theme', theme);
}

document.addEventListener('DOMContentLoaded', function () {
    // --- Sidebar toggle ---
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

    // --- Nav group toggle ---
    document.querySelectorAll('.nav-group-toggle').forEach(function (groupToggle) {
        var group = groupToggle.closest('.nav-group');
        groupToggle.addEventListener('click', function () {
            group.classList.toggle('collapsed');
        });
        groupToggle.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); groupToggle.click(); }
        });
    });

    // --- App bar search widget ---
    var searchWrap = document.getElementById('appbar-search');
    var searchBtn = document.getElementById('search-toggle');
    var searchInput = document.getElementById('search-input');
    var searchResultsNav = document.getElementById('search-results-nav');
    var searchResultsLink = document.getElementById('search-results-link');
    if (!searchWrap || !searchBtn || !searchInput) return;

    var locked = false;
    var typed = false;

    // Hover: expand the input
    searchWrap.addEventListener('mouseenter', function () {
        if (!locked) searchWrap.classList.add('expanded');
    });

    // Mouse leave: collapse if not locked and nothing typed
    searchWrap.addEventListener('mouseleave', function () {
        if (!locked && !typed) {
            searchWrap.classList.remove('expanded');
        }
    });

    // Typing: mark as typed so hover-out doesn't hide it, and lock
    searchInput.addEventListener('input', function () {
        if (searchInput.value.length > 0) {
            typed = true;
            locked = true;
            searchWrap.classList.add('locked');
            searchWrap.classList.remove('expanded');
        } else {
            typed = false;
        }
    });

    // Click magnifying glass: toggle lock
    searchBtn.addEventListener('click', function () {
        if (locked) {
            // If locked with text, submit the search
            if (searchInput.value.trim()) {
                submitSearch(searchInput.value.trim());
            }
            // Unlock and collapse
            locked = false;
            typed = false;
            searchWrap.classList.remove('locked', 'expanded');
            searchInput.blur();
        } else {
            // Lock open
            locked = true;
            searchWrap.classList.add('locked');
            searchWrap.classList.remove('expanded');
            searchInput.focus();
        }
    });

    // Enter key: submit search
    searchInput.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && searchInput.value.trim()) {
            e.preventDefault();
            submitSearch(searchInput.value.trim());
            locked = false;
            typed = false;
            searchWrap.classList.remove('locked', 'expanded');
            searchInput.blur();
        }
        // Escape: cancel
        if (e.key === 'Escape') {
            locked = false;
            typed = false;
            searchInput.value = '';
            searchWrap.classList.remove('locked', 'expanded');
            searchInput.blur();
        }
    });

    function submitSearch(query) {
        // Persist search state so the nav item survives page navigation
        localStorage.setItem('lastSearchQuery', query);
        if (searchResultsNav) searchResultsNav.style.display = '';
        if (searchResultsLink) {
            searchResultsLink.href = '/search?q=' + encodeURIComponent(query);
            searchResultsLink.classList.add('active');
        }
        window.location.href = '/search?q=' + encodeURIComponent(query);
    }

    // Restore Search Results nav item from localStorage on any page
    var savedQuery = localStorage.getItem('lastSearchQuery');
    if (savedQuery && searchResultsNav && searchResultsLink) {
        searchResultsNav.style.display = '';
        searchResultsLink.href = '/search?q=' + encodeURIComponent(savedQuery);
    }

    // On the search page, show the Search Results nav item and mark it active
    if (window.location.pathname === '/search') {
        if (searchResultsNav) searchResultsNav.style.display = '';
        if (searchResultsLink) searchResultsLink.classList.add('active');
        var params = new URLSearchParams(window.location.search);
        var q = params.get('q');
        if (q) {
            searchInput.value = q;
            localStorage.setItem('lastSearchQuery', q);
        }

        // Clear search button
        var clearBtn = document.getElementById('clear-search-btn');
        if (clearBtn) {
            clearBtn.addEventListener('click', function () {
                localStorage.removeItem('lastSearchQuery');
                if (searchResultsNav) searchResultsNav.style.display = 'none';
                window.location.href = '/';
            });
        }
    }
});
