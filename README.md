## Load Testing (Burst Script)

To simulate a high-concurrency ticket-buying stampede, run the provided `Burst.java` script. It uses Java's built-in HTTP client and virtual threads to fire concurrent requests.

Because the application is deployed on a free-tier hosting provider (0.1 CPU, limited DB connections), the test limits in-flight requests to 50 to prevent network timeouts.

**Requirements:** Java 21+ installed locally.

**Command:**
bash
java "-Dreqs=2000" "-Dseats=500" "-Dinflight=50" scripts/Burst.java https://seat-reservation-3m30.onrender.com admin-secret

*Note: The script includes a 30-to-60-second cooldown between the identity phase and the final stampede to allow the free-tier database connection pool to recover.*