package SSGA_Backend.security;

import java.io.Console;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashTool {

    public static void main(String[] args) {
        Console console = System.console();

        if (console == null) {
            throw new IllegalStateException("Run from a normal terminal.");
        }

        char[] password = console.readPassword("Enter password: ");

        String hash = new BCryptPasswordEncoder().encode(new String(password));

        System.out.println("PASSWORD_HASH=" + hash);
    }
}