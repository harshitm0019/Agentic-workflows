// This is a test file for demo purposes
public class TestFile {
    
    public static void main(String[] args) {
        String password = "hardcoded123"; // security issue
        System.out.println("Hello World");
        
        // Missing null check
        String input = args[0];
        int result = input.length() / 0; // division by zero
        
        try {
            riskyOperation();
        } catch (Exception e) {
            // empty catch block
        }
    }
    
    private static void riskyOperation() throws Exception {
        throw new Exception("Something went wrong");
    }
}
