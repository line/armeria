.. _advanced-custom-attribute:

``RequestContext`` custom attributes
====================================

When you are using multiple decorators, you might want to pass some value to the next decorator.
You can do this by attaching attributes to a :api:`RequestContext`. To attach an attribute,
you need to define an ``AttributeKey`` first:

.. code-block:: java

    import io.netty.util.AttributeKey;

    public final class MyAttributeKeys {
        public static final AttributeKey<Integer> INT_ATTR =
                AttributeKey.valueOf(MyAttributeKeys.class, "INT_ATTR");
        public static final AttributeKey<MyBean> BEAN_ATTR =
                AttributeKey.valueOf(MyAttributeKeys.class, "BEAN_ATTR");
        ...
    }

Then, you can access them via ``RequestContext.attr(AttributeKey)``:

.. code-block:: java

    // Setting
    ctx.attr(INT_ATTR).set(42);
    MyBean myBean = new MyBean();
    ctx.attr(BEAN_ATTR).set(new MyBean());

    // Getting
    Integer i = ctx.attr(INT_ATTR).get(); // i == 42
    MyBean bean = ctx.attr(BEAN_ATTR).get(); // bean == myBean

You can also iterate over all the attributes in a context using ``RequestContext.attrs()``:

.. code-block:: java

    for (Attribute<?> a : ctx.attrs()) {
        System.err.println(a.key() + ": " + a.get());
    }

If you are only interested in checking whether an attribute exists or not, you should use
``RequestContext.hasAttr(AttributeKey)``. It does not perform any allocation for a non-existent attribute
unlike ``RequestContext.attr(AttributeKey)`` does:

.. code-block:: java

    if (ctx.hasAttr(INT_ATTR)) {
        int i = ctx.attr(INT_ATTR).get();
        ...
    }

.. note::

    You can do this using :api:`RequestLog` as well. If you invoke ``RequestLog.attr(AttributeKey)``,
    ``RequestLog.attrs()`` and ``RequestLog.hasAttr(AttributeKey)``, the :api:`RequestLog` simply delegates
    the call to the :api:`RequestContext` that it belongs to.
