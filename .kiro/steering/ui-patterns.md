# UI and Architecture Patterns

This project follows consistent patterns for building features. Use these conventions when adding new functionality.

## Frontend Architecture

- Vanilla JavaScript (no frameworks), one JS file per page/feature
- Each JS module wraps in `document.addEventListener('DOMContentLoaded', function () { ... })`
- Early-exit guard: `if (!mainElement) return;` at the top
- All DOM references declared as `var` at the top of the closure
- Data loaded via `fetch('/api/...')` with `.then()` chains (no async/await)

## CRUD Page Pattern

Every CRUD page follows this structure:

1. **Template** (`.hbs`): Card layout with title, optional refresh button, form section, list container, and dialog backdrops
2. **JavaScript**: `loadAll()` → fetch data → `renderList()` → build `<table class="data-table">` with `<thead>` and `<tbody>`
3. **Actions column**: `<td class="conn-actions">` with `icon-btn-sm` buttons using Material Icons
4. **Dialogs**: Use `face-dialog-backdrop` class for modal overlays, with close button (`.dup-overlay-close`), title (`.face-dialog-title`), content, and actions (`.face-dialog-actions`)
5. **Escape to close**: All dialogs listen for `Escape` key and backdrop click

## Action Button Conventions

- Edit: `edit` icon, color `#5d4037`
- Delete: `delete` icon, color `#c62828`, always with `confirm()` dialog
- Start/Play: `play_arrow` icon, color `#2e7d32`, with `confirm()` for long-running operations
- Cancel: `cancel` icon, color `#e65100`, with `confirm()` dialog
- Settings/Override: `tune` icon, color `#5d4037`
- Refresh: `refresh` icon, color `#5d4037`

## Status Chips

Use `<span class="status-chip {class}">` with these classes:
- `status-active` — green, for active/completed/online states
- `status-pending` — amber, for created/offline/waiting states
- `status-inactive` — red/grey, for error/inactive states

## Settings Persistence Pattern

- UI settings that should persist across sessions go through `GET/PUT /api/settings`
- Load on page init, save on change with immediate `fetch` PUT
- SettingsManager applies defaults via `putIfAbsent` in `applyDefaults()`
- New settings keys: add default in `SettingsManager.applyDefaults()`, add UI control, wire load/save in JS

## Dropdown Conventions

- When offering a choice between "default" and named items (connections, etc.), use a single `<select>` with the default as the first `<option value="">Default Label</option>` followed by named items
- Include type in parentheses for disambiguation: `"My NAS (SMB)"`
- Do NOT use radio buttons + conditional secondary dropdowns — use one combined dropdown

## Backend API Pattern

- All CRUD endpoints in `ApiController.register()` as lambda route handlers
- JSON persistence: read list from file → modify → write back (no partial updates)
- Index-based addressing for ordered lists (connections, templates)
- UUID-based addressing for jobs and tasks
- Validation before activation (connections), confirmation before destructive actions (UI-side)

## Data Storage

- SQLite for file metadata (high-volume, queryable)
- JSON files in `{data_dir}/.ui-state/` for configuration data (connections, templates, jobs, settings)
- JSON files use `ObjectMapper.writerWithDefaultPrettyPrinter()` for human-readable output

## HTML Escaping

Every JS module defines a local `esc()` function:
```javascript
function esc(str) {
    var div = document.createElement('div');
    div.appendChild(document.createTextNode(str));
    return div.innerHTML;
}
```

## Table Cell Styling

- Dates: `style="font-size:12px;color:var(--text-secondary);"`
- Long text cells: `style="max-width:150px;word-wrap:break-word;overflow-wrap:break-word;"`
- Empty/null values: display `'—'`
