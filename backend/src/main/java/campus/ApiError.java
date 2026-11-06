package campus;

public class ApiError extends RuntimeException {
    final int status;
    final String code;
    public ApiError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    static void require(boolean condition, int status, String code, String message) {
        if (!condition) throw new ApiError(status, code, message);
    }
}
