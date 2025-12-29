package tabsequencer;

import java.io.Serializable;
/**
 * Pair 
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

public class Pair<A, B> implements Serializable {

    @Override
    public String toString() {
        return "Pair [a=" + a + ", b=" + b + "]";
    }

    public final A a;

    public final B b;

    public Pair(A a, B b) {
        this.a = a;
        this.b = b;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((a == null) ? 0 : a.hashCode());
        result = prime * result + ((b == null) ? 0 : b.hashCode());
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
        Pair<?, ?> other = (Pair<?, ?>) obj;
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
        return true;
    }
}