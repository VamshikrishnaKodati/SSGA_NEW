package SSGA_Backend.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class TestPassword {
    public static void main(String[] args) {
        String password = "Password@123";
        String hash = "$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3";
        
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        boolean matches = encoder.matches(password, hash);
        
        System.out.println("Password: " + password);
        System.out.println("Hash: " + hash);
        System.out.println("Matches: " + matches);
        
        // Generate new hash
        String newHash = encoder.encode(password);
        System.out.println("New hash: " + newHash);
        System.out.println("New matches: " + encoder.matches(password, newHash));
    }
}