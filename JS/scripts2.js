// Function to validate form and show appropriate alert
function validateForm() {
    // Example: Assuming you have a form with an input field with id="name"
    const nameInput = document.getElementById('name').value.trim();

    if (nameInput === "") {
        alert("Please fill out the required fields.");
        return false; // Prevent form submission
    } else {
        alert("Form submitted successfully!");
        return true; // Allow form submission
    }
}

// Attach the validateForm function to the form's onsubmit event
document.getElementById('myForm').onsubmit = function () {
    return validateForm();
};