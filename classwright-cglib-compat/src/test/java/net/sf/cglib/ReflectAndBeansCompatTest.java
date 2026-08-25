package net.sf.cglib;

import net.sf.cglib.beans.BeanCopier;
import net.sf.cglib.beans.BeanGenerator;
import net.sf.cglib.beans.BeanMap;
import net.sf.cglib.beans.BulkBean;
import net.sf.cglib.beans.ImmutableBean;
import net.sf.cglib.core.Signature;
import net.sf.cglib.reflect.ConstructorDelegate;
import net.sf.cglib.reflect.FastClass;
import net.sf.cglib.reflect.FastConstructor;
import net.sf.cglib.reflect.FastMethod;
import net.sf.cglib.reflect.MethodDelegate;
import net.sf.cglib.reflect.MulticastDelegate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rest of the reproduced surface, exercised the way an application would.
 *
 * <p>As with {@code DropInCompatibilityTest}, nothing here imports {@code com.classwright}.
 */
class ReflectAndBeansCompatTest {

    /** A conventional bean. */
    public static class Order {

        private String id;
        private int quantity;

        public Order() {
        }

        public Order(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public String describe() {
            return id + "/" + quantity;
        }
    }

    /** The copy target: {@code quantity} matches, {@code note} does not exist on the source. */
    public static class OrderView {

        private String id;
        private int quantity;
        private String note;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    /** A single-method interface matching {@link Order#describe()}, for {@code MethodDelegate}. */
    public interface OrderDescriber {
        String describe();
    }

    /** A void, single-argument interface for the {@code MulticastDelegate} fan-out. */
    public interface OrderAuditor {
        void record(String event);
    }

    /** Matches the {@code Order(String)} constructor, for {@code ConstructorDelegate}. */
    public interface OrderFactory {
        Order make(String id);
    }

    private static Order sample() {
        Order order = new Order("A-1");
        order.setQuantity(3);
        return order;
    }

    @Test
    @DisplayName("FastClass invokes methods and constructors by index")
    void fastClassWorks() throws Exception {
        FastClass fast = FastClass.create(Order.class);

        int index = fast.getIndex("getQuantity", new Class[0]);
        assertTrue(index >= 0);
        assertEquals(3, fast.invoke(index, sample(), new Object[0]));

        assertEquals("A-1/3", fast.invoke("describe", new Class[0], sample(), new Object[0]));
        assertEquals("B-2", ((Order) fast.newInstance(
                new Class[]{String.class}, new Object[]{"B-2"})).getId());
    }

    @Test
    @DisplayName("FastClass finds a method by Signature")
    void fastClassFindsBySignature() {
        FastClass fast = FastClass.create(Order.class);

        int bySignature = fast.getIndex(new Signature("getQuantity", "()I"));

        assertEquals(fast.getIndex("getQuantity", new Class[0]), bySignature);
    }

    @Test
    @DisplayName("FastMethod and FastConstructor carry their index")
    void fastMembersWork() throws Exception {
        FastClass fast = FastClass.create(Order.class);

        FastMethod describe = fast.getMethod("describe", new Class[0]);
        assertEquals("A-1/3", describe.invoke(sample(), new Object[0]));
        assertEquals(String.class, describe.getReturnType());

        FastConstructor constructor = fast.getConstructor(new Class[]{String.class});
        assertEquals("C-3", ((Order) constructor.newInstance(new Object[]{"C-3"})).getId());
    }

    @Test
    @DisplayName("MethodDelegate binds an instance method to an interface")
    void methodDelegateWorks() {
        // The factory returns Object rather than MethodDelegate — the documented difference —
        // and the cast to the requested interface, the way the result is actually used, is
        // unchanged.
        OrderDescriber bound = (OrderDescriber) MethodDelegate.create(sample(), "describe",
                OrderDescriber.class);

        assertEquals("A-1/3", bound.describe());
    }

    @Test
    @DisplayName("MulticastDelegate fans one call out to two targets, in order")
    void multicastDelegateWorks() {
        List<String> log = new ArrayList<>();
        OrderAuditor first = event -> log.add("first:" + event);
        OrderAuditor second = event -> log.add("second:" + event);

        // The declared type is inferred, because create() returns the underlying delegate type —
        // the documented difference. add() copies rather than mutates, exactly as in CGLib, so
        // the result must be reassigned.
        var fanOut = MulticastDelegate.create(OrderAuditor.class);
        fanOut = fanOut.add(first).add(second);

        ((OrderAuditor) fanOut).record("shipped");

        assertEquals(List.of("first:shipped", "second:shipped"), log);
    }

    @Test
    @DisplayName("ConstructorDelegate turns a constructor into a factory interface")
    void constructorDelegateWorks() {
        OrderFactory factory = (OrderFactory) ConstructorDelegate.create(Order.class,
                OrderFactory.class);

        assertEquals("D-4", factory.make("D-4").getId());
    }

    @Test
    @DisplayName("BeanCopier moves matching properties")
    void beanCopierWorks() {
        OrderView view = new OrderView();
        view.setNote("untouched");

        BeanCopier.create(Order.class, OrderView.class, false).copy(sample(), view, null);

        assertEquals("A-1", view.getId());
        assertEquals(3, view.getQuantity());
        assertEquals("untouched", view.getNote(), "an unmatched property is left alone");
    }

    @Test
    @DisplayName("BeanCopier applies a Converter")
    void beanCopierConverts() {
        OrderView view = new OrderView();

        BeanCopier.create(Order.class, OrderView.class, true).copy(sample(), view,
                (value, target, context) ->
                        "setId".equals(context) ? "converted" : value);

        assertEquals("converted", view.getId());
        assertEquals(3, view.getQuantity());
    }

    @Test
    @DisplayName("BulkBean reads and writes property arrays")
    void bulkBeanWorks() {
        BulkBean bulk = BulkBean.create(Order.class,
                new String[]{"getId", "getQuantity"},
                new String[]{"setId", "setQuantity"},
                new Class[]{String.class, int.class});

        assertArrayEquals(new Object[]{"A-1", 3}, bulk.getPropertyValues(sample()));

        Order target = new Order();
        bulk.setPropertyValues(target, new Object[]{"B-2", 7});
        assertEquals("B-2/7", target.describe());
    }

    @Test
    @DisplayName("BeanMap reads and writes by property name")
    void beanMapWorks() {
        Order order = sample();
        BeanMap map = BeanMap.create(order);

        assertEquals("A-1", map.get("id"));
        map.put("quantity", 11);

        assertEquals(11, order.getQuantity(), "a BeanMap is a view");
        assertTrue(map.keySet().contains("id"));
        assertEquals(int.class, map.getPropertyType("quantity"));
    }

    @Test
    @DisplayName("BeanGenerator builds a bean from a runtime shape")
    void beanGeneratorWorks() {
        BeanGenerator generator = new BeanGenerator();
        generator.addProperty("name", String.class);
        generator.addProperty("count", int.class);

        Object bean = generator.create();
        BeanMap map = BeanMap.create(bean);
        map.put("name", "generated");
        map.put("count", 5);

        assertEquals("generated", map.get("name"));
        assertEquals(5, map.get("count"));
    }

    @Test
    @DisplayName("ImmutableBean allows reads and refuses writes")
    void immutableBeanWorks() {
        Order readOnly = (Order) ImmutableBean.create(sample());

        assertEquals("A-1", readOnly.getId());
        assertThrows(IllegalStateException.class, () -> readOnly.setId("changed"));
    }
}
