package com.classwright.beans;

import com.classwright.ClasswrightException;
import com.classwright.beans.fixtures.Order;
import com.classwright.beans.fixtures.OrderDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the bean utilities. */
class BeansTest {

    private static Order sampleOrder() {
        Order order = new Order();
        order.setId("A-1");
        order.setQuantity(3);
        order.setTotal(999L);
        order.setWeight(1.5);
        order.setUrgent(true);
        order.setTags(new String[]{"x", "y"});
        return order;
    }

    @Nested
    @DisplayName("BeanCopier")
    class Copying {

        @Test
        @DisplayName("copies properties that match by name and type")
        void copiesMatchingProperties() {
            OrderDto dto = new OrderDto();

            BeanCopier.create(Order.class, OrderDto.class, false)
                    .copy(sampleOrder(), dto, null);

            assertEquals("A-1", dto.getId());
            assertEquals(3, dto.getQuantity());
            assertTrue(dto.isUrgent(), "a boolean read through isX should copy");
        }

        @Test
        @DisplayName("skips properties whose types differ")
        void skipsMismatchedTypes() {
            // Order.total is a long; OrderDto.total is a String. Without a converter there is no
            // way to move it, and CGLib's behaviour -- which this matches -- is to skip silently.
            OrderDto dto = new OrderDto();

            BeanCopier.create(Order.class, OrderDto.class, false)
                    .copy(sampleOrder(), dto, null);

            assertNull(dto.getTotal());
        }

        @Test
        @DisplayName("skips properties that exist on only one side")
        void skipsUnmatchedProperties() {
            OrderDto dto = new OrderDto();
            dto.setMissing("untouched");

            BeanCopier.create(Order.class, OrderDto.class, false)
                    .copy(sampleOrder(), dto, null);

            assertEquals("untouched", dto.getMissing());
        }

        @Test
        @DisplayName("a converter bridges types that do not match")
        void convertsValues() {
            OrderDto dto = new OrderDto();

            BeanCopier.create(Order.class, OrderDto.class, true).copy(sampleOrder(), dto,
                    (value, targetType, setterName) ->
                            targetType == String.class && value != null
                                    ? String.valueOf(value)
                                    : value);

            assertEquals("999", dto.getTotal(), "the long should have been converted");
            assertEquals("A-1", dto.getId());
            assertEquals(3, dto.getQuantity(), "primitives round-trip through the converter");
        }

        @Test
        @DisplayName("a converter returning null yields the zero value for a primitive")
        void converterNullBecomesZero() {
            OrderDto dto = new OrderDto();
            dto.setQuantity(42);

            BeanCopier.create(Order.class, OrderDto.class, true)
                    .copy(sampleOrder(), dto, (value, targetType, setterName) -> null);

            assertEquals(0, dto.getQuantity());
            assertNull(dto.getId());
        }

        @Test
        @DisplayName("the setter name is passed to the converter")
        void converterSeesTheSetterName() {
            OrderDto dto = new OrderDto();

            BeanCopier.create(Order.class, OrderDto.class, true).copy(sampleOrder(), dto,
                    (value, targetType, setterName) -> {
                        if ("setId".equals(setterName)) {
                            return "overridden";
                        }
                        // With a converter every pairing is attempted, including long -> String,
                        // so the converter has to cope with the types it is handed.
                        return targetType == String.class && value != null
                                ? String.valueOf(value) : value;
                    });

            assertEquals("overridden", dto.getId());
        }

        @Test
        @DisplayName("explains which properties it will move")
        void describesTheMapping() {
            // "Why did that field not copy" deserves an answer.
            String mapping = BeanCopier.create(Order.class, OrderDto.class, false)
                    .describeMapping();

            assertTrue(mapping.contains("getId/setId"), mapping);
            assertFalse(mapping.contains("setTotal"), "total does not match without a converter");
        }

        @Test
        @DisplayName("copies a bean to another of its own type")
        void copiesToSameType() {
            Order copy = new Order();

            BeanCopier.create(Order.class, Order.class, false).copy(sampleOrder(), copy, null);

            assertEquals("A-1", copy.getId());
            assertEquals(999L, copy.getTotal());
            assertEquals(1.5, copy.getWeight());
            assertArrayEquals(new String[]{"x", "y"}, copy.getTags());
            assertEquals("fixed", copy.getReadOnly(), "a read-only property is left as it was");
        }
    }

    @Nested
    @DisplayName("BulkBean")
    class Bulk {

        private BulkBean bulk() {
            return BulkBean.create(Order.class,
                    new String[]{"getId", "getQuantity", "getTotal", "isUrgent"},
                    new String[]{"setId", "setQuantity", "setTotal", "setUrgent"},
                    new Class<?>[]{String.class, int.class, long.class, boolean.class});
        }

        @Test
        @DisplayName("reads properties into an array")
        void readsIntoArray() {
            Object[] values = bulk().getPropertyValues(sampleOrder());

            assertArrayEquals(new Object[]{"A-1", 3, 999L, true}, values);
        }

        @Test
        @DisplayName("writes properties from an array")
        void writesFromArray() {
            Order order = new Order();

            bulk().setPropertyValues(order, new Object[]{"B-2", 7, 12L, false});

            assertEquals("B-2", order.getId());
            assertEquals(7, order.getQuantity());
            assertEquals(12L, order.getTotal());
            assertFalse(order.isUrgent());
        }

        @Test
        @DisplayName("a null for a primitive property fails with the index, not a silent zero")
        void nullForPrimitiveFailsWithIndex() {
            // Strict, deliberately: writing zero for an accidental null hides real bugs, and
            // CGLib failed here too. A null for a *reference* property (id, position 0) is an
            // ordinary value and is written before the primitive position fails.
            Order order = sampleOrder();

            BulkAccessException failure = assertThrows(BulkAccessException.class,
                    () -> bulk().setPropertyValues(order, new Object[]{null, null, null, null}));

            assertEquals(1, failure.propertyIndex(),
                    "position 0 is a String and accepts null; position 1 is the first primitive");
            assertInstanceOf(NullPointerException.class, failure.getCause());
            assertNull(order.getId(), "positions before the failure were already written");
            assertEquals(sampleOrder().getQuantity(), order.getQuantity(),
                    "the failing position must not have been written");
        }

        @Test
        @DisplayName("a null accessor name skips that position in that direction")
        void nullAccessorSkipsPosition() {
            BulkBean readOnly = BulkBean.create(Order.class,
                    new String[]{"getId"}, new String[]{null}, new Class<?>[]{String.class});
            Order order = sampleOrder();

            readOnly.setPropertyValues(order, new Object[]{"ignored"});

            assertEquals("A-1", order.getId(), "with no setter, nothing should have been written");
            assertArrayEquals(new Object[]{"A-1"}, readOnly.getPropertyValues(order));
        }

        @Test
        @DisplayName("rejects arrays of differing lengths")
        void rejectsMismatchedArrays() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> BulkBean.create(Order.class, new String[]{"getId"}, new String[0],
                            new Class<?>[]{String.class}));

            assertTrue(failure.getMessage().contains("same length"), failure.getMessage());
        }

        @Test
        @DisplayName("names a missing accessor and its position")
        void reportsMissingAccessor() {
            ClasswrightException failure = assertThrows(ClasswrightException.class,
                    () -> BulkBean.create(Order.class, new String[]{"getNothing"},
                            new String[]{"setId"}, new Class<?>[]{String.class}));

            assertTrue(failure.getMessage().contains("getNothing"), failure.getMessage());
            assertTrue(failure.getMessage().contains("position 0"), failure.getMessage());
        }
    }

    @Nested
    @DisplayName("BeanMap")
    class Mapping {

        @Test
        @DisplayName("reads properties by name")
        void readsByName() {
            BeanMap map = BeanMap.create(sampleOrder());

            assertEquals("A-1", map.get("id"));
            assertEquals(3, map.get("quantity"));
            assertEquals(999L, map.get("total"));
            assertEquals(true, map.get("urgent"), "isX is read as the property 'urgent'");
        }

        @Test
        @DisplayName("writes properties by name, and the bean sees it")
        void writesByName() {
            Order order = sampleOrder();
            BeanMap map = BeanMap.create(order);

            Object previous = map.put("quantity", 11);

            assertEquals(3, previous);
            assertEquals(11, order.getQuantity(), "a BeanMap is a view, not a copy");
        }

        @Test
        @DisplayName("exposes the property names as its key set")
        void exposesKeys() {
            Set<String> keys = BeanMap.create(sampleOrder()).keySet();

            assertTrue(keys.containsAll(Set.of("id", "quantity", "total", "weight", "urgent",
                    "tags", "readOnly", "writeOnly")), keys.toString());
            assertFalse(keys.contains("describe"), "a plain method is not a property");
        }

        @Test
        @DisplayName("an unknown key reads null and writes nothing")
        void ignoresUnknownKeys() {
            BeanMap map = BeanMap.create(sampleOrder());

            assertNull(map.get("nonexistent"));
            assertNull(map.put("nonexistent", "x"), "the key set is fixed by the bean's shape");
        }

        @Test
        @DisplayName("a read-only property reads but does not write")
        void handlesOneSidedProperties() {
            Order order = sampleOrder();
            BeanMap map = BeanMap.create(order);

            assertEquals("fixed", map.get("readOnly"));
            map.put("readOnly", "changed");
            assertEquals("fixed", order.getReadOnly());

            map.put("writeOnly", "written");
            assertEquals("written", order.peekWriteOnly());
            assertNull(map.get("writeOnly"), "there is no getter to read it back with");
        }

        @Test
        @DisplayName("reports property types")
        void reportsPropertyTypes() {
            BeanMap map = BeanMap.create(sampleOrder());

            assertEquals(int.class, map.getPropertyType("quantity"));
            assertEquals(String.class, map.getPropertyType("id"));
            assertNull(map.getPropertyType("nonexistent"));
        }

        @Test
        @DisplayName("behaves as a Map")
        void behavesAsAMap() {
            BeanMap map = BeanMap.create(sampleOrder());

            assertEquals(map.keySet().size(), map.size());
            assertTrue(map.containsKey("id"));
            assertTrue(map.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().equals("id")
                            && entry.getValue().equals("A-1")));
        }

        @Test
        @DisplayName("rebinds to another bean without regenerating the class")
        void rebindsToAnotherBean() {
            BeanMap first = BeanMap.create(sampleOrder());
            Order other = new Order();
            other.setId("B-2");

            BeanMap second = first.newInstance(other);

            assertNotSame(first, second);
            assertSame(first.getClass(), second.getClass());
            assertEquals("A-1", first.get("id"));
            assertEquals("B-2", second.get("id"));
        }

        @Test
        @DisplayName("a null or non-String key reads null and writes nothing")
        void toleratesForeignKeys() {
            // The generated dispatch switches on key.hashCode() and guards with String.equals,
            // so an Integer whose hash collides with a property name must still miss, and null
            // must be answered before the dispatch ever sees it.
            Order order = sampleOrder();
            BeanMap map = BeanMap.create(order);

            assertNull(map.get(null));
            assertNull(map.put(null, "x"));
            assertNull(map.get(42));
            assertNull(map.get("id".hashCode()), "a colliding hash is not a matching key");
            assertEquals(3, order.getQuantity(), "nothing may have been written");
        }

        /** Two property names with the same {@code String.hashCode} ("aa" and "bB" collide). */
        public static class Colliding {

            private int aa = 1;
            private int bB = 2;

            public int getAa() {
                return aa;
            }

            public void setAa(int aa) {
                this.aa = aa;
            }

            public int getbB() {
                return bB;
            }

            public void setbB(int bB) {
                this.bB = bB;
            }
        }

        @Test
        @DisplayName("property names with colliding hashes each reach their own accessor")
        void survivesHashCollisions() {
            assertEquals("aa".hashCode(), "bB".hashCode(),
                    "the fixture must genuinely collide or this test pins nothing");
            Colliding bean = new Colliding();
            BeanMap map = BeanMap.create(bean);

            assertEquals(1, map.get("aa"));
            assertEquals(2, map.get("bB"));
            map.put("bB", 20);
            assertEquals(20, bean.getbB());
            assertEquals(1, bean.getAa(), "the colliding neighbour must be untouched");
        }
    }

    @Nested
    @DisplayName("BeanGenerator")
    class Generating {

        @Test
        @DisplayName("builds a bean from a runtime shape")
        void buildsABean() {
            Object bean = new BeanGenerator()
                    .setNeighbour(Order.class)
                    .addProperty("name", String.class)
                    .addProperty("count", int.class)
                    .addProperty("active", boolean.class)
                    .create();

            BeanMap map = BeanMap.create(bean);
            map.put("name", "generated");
            map.put("count", 5);
            map.put("active", true);

            assertEquals("generated", map.get("name"));
            assertEquals(5, map.get("count"));
            assertEquals(true, map.get("active"));
        }

        @Test
        @DisplayName("uses getX even for boolean properties, exactly as CGLib named them")
        void usesGetForBooleans() throws Exception {
            Class<?> generated = new BeanGenerator()
                    .setNeighbour(Order.class)
                    .addProperty("ready", boolean.class)
                    .createClass();

            // CGLib's BeanGenerator did not follow the JavaBeans isX convention, and reflective
            // code migrated from it looks the accessor up by the getX name.
            assertEquals(boolean.class, generated.getMethod("getReady").getReturnType());
            assertThrows(NoSuchMethodException.class, () -> generated.getMethod("isReady"));
        }

        @Test
        @DisplayName("the generated class is resolvable, because the bean utilities need a name")
        void generatesNamedClasses() throws Exception {
            // Not hidden, and deliberately so: BeanMap, BulkBean and BeanCopier all generate code
            // that calls the accessors *by name*, and a hidden class has none they could use. The
            // cost -- these never unload -- is documented rather than worked around.
            Class<?> generated = new BeanGenerator()
                    .setNeighbour(Order.class)
                    .addProperty("name", String.class)
                    .createClass();

            assertFalse(generated.isHidden());
            assertSame(generated, Class.forName(generated.getName(), false,
                    generated.getClassLoader()));
        }

        @Test
        @DisplayName("an identical shape reuses the generated class; a different one does not")
        void cachesIdenticalShapes() {
            Class<?> first = new BeanGenerator().setNeighbour(Order.class)
                    .addProperty("name", String.class).createClass();
            Class<?> second = new BeanGenerator().setNeighbour(Order.class)
                    .addProperty("name", String.class).createClass();
            Class<?> different = new BeanGenerator().setNeighbour(Order.class)
                    .addProperty("name", String.class)
                    .addProperty("count", int.class).createClass();

            // These are named classes, retained for the life of their loader; CGLib reused the
            // class for an identical configuration, and repeated generation without reuse is a
            // metaspace leak one shape at a time.
            assertSame(first, second);
            assertNotSame(first, different);
        }

        @Test
        @DisplayName("refuses to generate a bean with no properties")
        void refusesEmptyBeans() {
            assertThrows(ClasswrightException.class, () -> new BeanGenerator().create());
        }
    }

    @Nested
    @DisplayName("ImmutableBean")
    class Immutability {

        @Test
        @DisplayName("reads pass through to the original")
        void readsPassThrough() {
            Order readOnly = (Order) ImmutableBean.create(sampleOrder());

            assertEquals("A-1", readOnly.getId());
            assertEquals(3, readOnly.getQuantity());
            assertTrue(readOnly.isUrgent());
        }

        @Test
        @DisplayName("writes throw, naming the method")
        void writesThrow() {
            Order readOnly = (Order) ImmutableBean.create(sampleOrder());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> readOnly.setId("changed"));

            assertTrue(failure.getMessage().contains("setId"), failure.getMessage());
            assertTrue(failure.getMessage().contains("immutable"), failure.getMessage());
        }

        @Test
        @DisplayName("it is a view: changes to the original are visible through it")
        void isAView() {
            // Prevents modification through this reference; it does not freeze the bean.
            Order original = sampleOrder();
            Order readOnly = (Order) ImmutableBean.create(original);

            original.setId("changed elsewhere");

            assertEquals("changed elsewhere", readOnly.getId());
        }

        @Test
        @DisplayName("non-accessor methods still work")
        void ordinaryMethodsWork() {
            Order readOnly = (Order) ImmutableBean.create(sampleOrder());

            assertEquals("A-1/3", readOnly.describe());
        }
    }

    @Test
    @DisplayName("property discovery follows the JavaBeans naming rules")
    void discoversProperties() {
        Map<String, BeanProperties.Property> properties = BeanProperties.of(Order.class);

        assertTrue(properties.containsKey("id"));
        assertTrue(properties.containsKey("urgent"), "isUrgent is the 'urgent' property");
        assertFalse(properties.containsKey("describe"), "a plain method is not a property");
        assertFalse(properties.get("readOnly").isWritable());
        assertFalse(properties.get("writeOnly").isReadable());
        assertEquals(int.class, properties.get("quantity").type());
    }

    /**
     * Setter pairing follows Introspector and CGLib, measured on both: a property's setter takes
     * the getter's type or a subtype (wider setters do not write the property), the most
     * specific compatible overload wins with no privilege for exactness, and write-only
     * properties resolve to the most specific setter.
     */
    @Nested
    @DisplayName("overloaded setter resolution")
    class SetterResolution {

        public class WiderSetter {

            private CharSequence value = "orig";

            public String getX() {
                return value.toString();
            }

            public void setX(CharSequence value) {
                this.value = value;
            }
        }

        public class NarrowerSetter {

            private CharSequence value = "orig";

            public CharSequence getX() {
                return value;
            }

            public void setX(String value) {
                this.value = value;
            }
        }

        public class OverloadedSetters {

            Class<?> lastSetterArgument;

            public Object getX() {
                return "x";
            }

            public void setX(Object value) {
                lastSetterArgument = Object.class;
            }

            public void setX(CharSequence value) {
                lastSetterArgument = CharSequence.class;
            }

            public void setX(String value) {
                lastSetterArgument = String.class;
            }
        }

        public class WriteOnlyOverloads {

            public void setX(Object value) {
            }

            public void setX(String value) {
            }
        }

        @Test
        @DisplayName("a wider setter does not write the property; it degrades to read-only")
        void widerSetterMakesReadOnly() {
            // Measured on Introspector and CGLib: getX():String pairs only with a setter taking
            // String or a subtype. setX(CharSequence) is not this property's writable half, and
            // treating it as one made a CGLib-read-only property writable after migration.
            Map<String, BeanProperties.Property> properties =
                    BeanProperties.of(WiderSetter.class);
            assertFalse(properties.get("x").isWritable());

            WiderSetter bean = new WiderSetter();
            BeanMap map = BeanMap.create(bean);
            assertNull(map.put("x", "hi"), "a read-only put answers null and writes nothing");
            assertEquals("orig", bean.getX());
        }

        @Test
        @DisplayName("a narrower setter is the property's writable half, as in CGLib")
        void narrowerSetterIsWritable() {
            // The mirror case the old backwards check rejected: getX():CharSequence with
            // setX(String) IS writable in Introspector and CGLib; the property's type stays the
            // getter's.
            Map<String, BeanProperties.Property> properties =
                    BeanProperties.of(NarrowerSetter.class);
            assertTrue(properties.get("x").isWritable());
            assertEquals(CharSequence.class, properties.get("x").type());

            NarrowerSetter bean = new NarrowerSetter();
            BeanMap.create(bean).put("x", "written");
            assertEquals("written", bean.getX().toString());
        }

        @Test
        @DisplayName("the most specific compatible overload wins; exactness has no privilege")
        void mostSpecificOverloadWins() {
            Map<String, BeanProperties.Property> properties =
                    BeanProperties.of(OverloadedSetters.class);

            assertEquals(String.class, properties.get("x").setter().getParameterTypes()[0],
                    "Introspector picks setX(String) here; the exact getter-type setX(Object) "
                            + "confers no privilege");

            OverloadedSetters bean = new OverloadedSetters();
            BeanMap.create(bean).put("x", "value");
            assertEquals(String.class, bean.lastSetterArgument);
        }

        @Test
        @DisplayName("write-only overloads also resolve to the most specific setter")
        void writeOnlyPicksMostSpecific() {
            Map<String, BeanProperties.Property> properties =
                    BeanProperties.of(WriteOnlyOverloads.class);

            assertFalse(properties.get("x").isReadable());
            assertEquals(String.class, properties.get("x").setter().getParameterTypes()[0]);
        }

        @Test
        @DisplayName("BeanCopier writes through the target's own resolved setter only")
        void copierUsesTheResolvedSetter() {
            // The two relations stay separate: within the target, the property's setter follows
            // the JavaBeans rules above; between beans, the source's read type must feed that
            // resolved setter. A wider setter must not be resurrected just to make a copy fit.
            BeanCopier copier = BeanCopier.create(WiderSetter.class, WiderSetter.class, false);

            WiderSetter destination = new WiderSetter();
            copier.copy(new WiderSetter(), destination, null);
            assertEquals("orig", destination.getX(),
                    "the target property is read-only, so nothing may be copied into it");
        }
    }

    /**
     * The is-form counts only for primitive {@code boolean}, exactly as Introspector and CGLib
     * define it. This model feeds BeanMap and BeanCopier, so a deviation here is not a naming
     * nicety — it changes which data a migrated copy or map read actually touches.
     */
    @Nested
    @DisplayName("boxed Boolean is-form")
    class BoxedBooleanIsForm {

        public class BoxedOnly {
            public Boolean isReady() {
                return Boolean.TRUE;
            }

            public void setReady(Boolean ready) {
            }
        }

        public class BoxedBoth {
            public Boolean isReady() {
                return Boolean.TRUE;
            }

            public Boolean getReady() {
                return Boolean.FALSE;
            }
        }

        public class PrimitiveBoth {
            public boolean isReady() {
                return true;
            }

            public boolean getReady() {
                return false;
            }
        }

        @Test
        @DisplayName("a boxed Boolean isX() is not a getter; the property degrades to write-only")
        void boxedIsFormIsNotAGetter() {
            Map<String, BeanProperties.Property> properties = BeanProperties.of(BoxedOnly.class);

            assertTrue(properties.containsKey("ready"), "the setter still makes a property");
            assertFalse(properties.get("ready").isReadable(),
                    "CGLib and Introspector recognise the is-form only for primitive boolean");
            assertTrue(properties.get("ready").isWritable());
        }

        @Test
        @DisplayName("with both accessors, the boxed property reads through getX(), as in CGLib")
        void boxedPropertyReadsThroughGetForm() {
            Map<String, BeanProperties.Property> properties = BeanProperties.of(BoxedBoth.class);

            assertEquals("getReady", properties.get("ready").getter().getName(),
                    "real CGLib resolves the boxed property through getReady() and reads false; "
                            + "preferring the boxed is-form returned different data");
        }

        @Test
        @DisplayName("for primitive boolean, the is-form still wins, per Introspector's rule")
        void primitiveIsFormStillWins() {
            Map<String, BeanProperties.Property> properties =
                    BeanProperties.of(PrimitiveBoth.class);

            assertEquals("isReady", properties.get("ready").getter().getName());
        }

        @Test
        @DisplayName("BeanMap sees the CGLib view: no readable 'ready', null on get")
        void beanMapFollowsTheModel() {
            BeanMap map = BeanMap.create(new BoxedOnly());

            assertNull(map.get("ready"), "a write-only property reads as null, as in CGLib");
            assertNull(map.getPropertyType("nowhere"));
        }

        @Test
        @DisplayName("BeanCopier does not copy through a boxed isX(), as CGLib did not")
        void beanCopierFollowsTheModel() {
            // Source offers only the boxed is-form; the target has a real setter. CGLib copied
            // nothing here, because the source has no JavaBeans getter for 'ready'.
            BeanCopier copier = BeanCopier.create(BoxedOnly.class, BoxedOnly.class, false);

            BoxedOnly destination = new BoxedOnly() {
                @Override
                public void setReady(Boolean ready) {
                    throw new AssertionError(
                            "nothing may be copied into 'ready'; the source has no getter");
                }
            };
            copier.copy(new BoxedOnly(), destination, null);
        }
    }
}
