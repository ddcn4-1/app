
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashTest {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = "password123";

        // 새로운 해시 3개 생성 (매번 다름)
        System.out.println("=== BCrypt 해시 생성 ===");
        for (int i = 1; i <= 3; i++) {
            String hash = encoder.encode(password);
            System.out.println("Hash " + i + ": " + hash);

            // 검증
            boolean matches = encoder.matches(password, hash);
            System.out.println("Matches: " + matches);
            System.out.println();
        }

        System.out.println("=== 기존 data.sql 해시 검증 ===");
        String existingHash = "$2a$10$2aCyXK9YjKqFpkLGV1bGMeN9mfmNkPn/tR5xD9vl.pTUo8qw5HT86";
        boolean existingMatches = encoder.matches(password, existingHash);
        System.out.println("기존 해시와 password123 매치: " + existingMatches);

        System.out.println("\n=== data.sql 업데이트용 ===");
        String newHash = encoder.encode(password);
        System.out.println("새로운 해시: " + newHash);
        System.out.println("\ndata.sql에서 다음과 같이 사용하세요:");
        System.out.println("password_hash = '" + newHash + "'");
    }
}