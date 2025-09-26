document.addEventListener("DOMContentLoaded", () => {
    const authButton = document.getElementById("authButton");
    const authOptions = document.getElementById("authOptions");
    const signInButton = document.getElementById("signInButton");
    const signUpButton = document.getElementById("signUpButton");
    const signInForm = document.getElementById("signInForm");
    const signUpForm = document.getElementById("signUpForm");

    authButton.addEventListener("click", () => {
        authOptions.classList.toggle("hidden");
    });

    signInButton.addEventListener("click", () => {
        signUpForm.classList.add("hidden");
        signInForm.classList.toggle("hidden");
    });

    signUpButton.addEventListener("click", () => {
        signInForm.classList.add("hidden");
        signUpForm.classList.toggle("hidden");
    });

    document.getElementById("registerForm").addEventListener("submit", (event) => {
        event.preventDefault();
        const newUsername = document.getElementById("newUsername").value.trim();
        if (!newUsername) {
            alert("Please enter a valid username to register.");
            return;
        }
        alert(`Welcome, ${newUsername}!`);
    });

    document.getElementById("loginForm").addEventListener("submit", (event) => {
        event.preventDefault();
        const username = document.getElementById("username").value.trim();
        if (!username) {
            alert("Please enter a valid username to log in.");
            return;
        }
        alert(`Welcome back, ${username}!`);
    });
});

function showMessage() {
    alert("Thanks for your interest! We'll contact you shortly for a personalized consultation.");
  }

  window.addEventListener('scroll', () => {
    document.querySelectorAll('.animated-box').forEach(el => {
      const boxTop = el.getBoundingClientRect().top;
      const trigger = window.innerHeight - 100;
      if (boxTop < trigger) {
        el.classList.add('reveal');
      }
    });
  });
  