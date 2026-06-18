package com.agentic.service;

import java.util.List;
import java.util.Map;

/**
 * A broken service class with intentional compilation errors
 * to test the failure-analysis workflow.
 */
public class UserService {

    // ERROR: Missing semicolons
    private String dbUrl = "jdbc:postgresql://localhost/users"
    private int maxRetries = 3

    // ERROR: Type mismatch - assigning String to int
    private int connectionTimeout = "thirty";

    // ERROR: Undefined type reference
    private DatabaseConnection connection;

    public List<Map<String, Object>> getActiveUsers() {
        // ERROR: Calling method on undefined variable
        var results = connection.query("SELECT * FROM users WHERE active = " + userInput);

        // ERROR: Missing return type compatibility
        return results.toString();
    }

    public void processUser(String userId) {
        // ERROR: Unchecked exception not handled
        int id = Integer.parseInt(userId);

        // ERROR: Array index out of bounds pattern
        String[] roles = new String[3];
        roles[5] = "admin";

        // ERROR: Null pointer dereference
        String name = null;
        int length = name.length();
    }
}
