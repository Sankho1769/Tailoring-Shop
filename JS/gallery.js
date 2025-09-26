const pages = document.querySelectorAll('.page');
let current = 0;

pages.forEach((page, index) => {
  page.style.zIndex = pages.length - index;
});

function updatePages() {
  pages.forEach((page, index) => {
    if (index < current) {
      page.classList.add('flipped');
    } else {
      page.classList.remove('flipped');
    }
  });
}

function nextPage() {
  if (current < pages.length) {
    current++;
    updatePages();
  }
}

function prevPage() {
  if (current > 0) {
    current--;
    updatePages();
  }
}
