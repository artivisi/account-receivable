// Generic typeahead lookup picker. Works with the `fragments :: lookupPicker` widget: a hidden field
// (data-picker-value, the submitted code), a search input (data-picker-search), and a results
// container (data-picker-results) that HTMX fills from the widget's lookup URL. Behaviour is wired by
// data-attributes + event delegation, so it applies to every picker on the page (debtor, invoice
// type, …) and to results swapped in later by HTMX.
(function () {
  function picker(el) { return el.closest('.lookup-picker'); }

  // Click a result option -> set the hidden code, show its label, close the list.
  document.addEventListener('click', function (e) {
    var opt = e.target.closest('[data-picker-option]');
    if (opt) {
      e.preventDefault();
      var p = picker(opt);
      if (!p) return;
      p.querySelector('[data-picker-value]').value = opt.getAttribute('data-code');
      p.querySelector('[data-picker-search]').value = opt.getAttribute('data-label');
      p.querySelector('[data-picker-results]').innerHTML = '';
      return;
    }
    // Click outside any picker -> close all result lists.
    if (!picker(e.target)) {
      document.querySelectorAll('[data-picker-results]').forEach(function (r) { r.innerHTML = ''; });
    }
  });

  // Editing the search text invalidates a prior pick (force a fresh selection before submit).
  document.addEventListener('input', function (e) {
    if (e.target.matches('[data-picker-search]')) {
      var p = picker(e.target);
      if (p) p.querySelector('[data-picker-value]').value = '';
    }
  });

  // Enter in the search field must not submit the surrounding form.
  document.addEventListener('keydown', function (e) {
    if (e.target.matches('[data-picker-search]') && e.key === 'Enter') e.preventDefault();
  });
})();
