// This is a test file for demo purposes
    public static void main(String[] args) {
        // Hardcoded sensitive information (password) removed.
        // TODO: Load password securely from environment variables, a secrets management system, or a secure configuration file.
        // For demonstration, we'll use a placeholder from an environment variable.
        String password = System.getenv("DB_PASSWORD");
        if (password == null) {
            System.err.println("Warning: DB_PASSWORD environment variable not set. Using a dummy password for demonstration.");
            password = "dummy_password"; // Fallback, but not for production use.
        }

        String input = "default"; // Variable declaration moved into main method.
            // empty catch block - bad practice
        }
        
        // New code to trigger webhook
        String sql = "SELECT * FROM users WHERE id = " + input; // SQL injection
    }
    
    private static void riskyOperation() throws Exception {
        throw new Exception("Something went wrong");
    }
}
