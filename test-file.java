// This is a test file for demo purposes
public class TestFile {
        // TODO: Load password securely from configuration or secrets management.
        // For demonstration, a placeholder is used. In a real application,
        // this should be loaded from environment variables, a secure configuration file,
        // or a secrets management system.
        String password = "secure_password_placeholder";

        String input; // Declare input outside try-block for wider scope
        try {
            if (args.length > 0) {
                input = args[0];
            } else {
                System.err.println("No command-line argument provided. Using default input.");
                input = "default_input"; // Provide a default or handle the error appropriately
            }
            int result = input.length(); // Fix: Removed division by zero
        String input = args[0];
        } catch (Exception e) {
            System.err.println("An error occurred: " + e.getMessage()); // Log the exception
        try {
            riskyOperation();
        } catch (Exception e) {
            // empty catch block - bad practice
        }
        
        // New code to trigger webhook
        String sql = "SELECT * FROM users WHERE id = " + input; // SQL injection
    }
    
    private static void riskyOperation() throws Exception {
        throw new Exception("Something went wrong");
    }
}
