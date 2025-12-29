package tabsequencer;
/**
 * Trio 
 *
 * <pre>
 * SOFTWARE HISTORY
 * Date           Ticket#       Engineer    Description
 * ----------- --------------- ----------- --------------------------
 * Jan 21, 2024   DCS #2029604  OWP         Initial baseline version
 * 
 * </pre>
 * 
 * @author OWP
*/

public class Trio<A, B, C> {
    public final A a;

    public final B b;

    public final C c;

    public Trio(A a, B b, C c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((a == null) ? 0 : a.hashCode());
        result = prime * result + ((b == null) ? 0 : b.hashCode());
        result = prime * result + ((c == null) ? 0 : c.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Trio<?, ?, ?> other = (Trio<?, ?, ?>) obj;
        if (a == null) {
            if (other.a != null) {
                return false;
            }
        } else if (!a.equals(other.a)) {
            return false;
        }
        if (b == null) {
            if (other.b != null) {
                return false;
            }
        } else if (!b.equals(other.b)) {
            return false;
        }
        if (c == null) {
            if (other.c != null) {
                return false;
            }
        } else if (!c.equals(other.c)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "Trio [a=" + a + ", b=" + b + ", c=" + c + "]";
    }
}