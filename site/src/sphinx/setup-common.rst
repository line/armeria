You may not need all Armeria modules depending on your use case. Please remove unused ones.

Armeria also provides its artifacts as a shaded JAR so that it can coexist with other components
better. The following is the list of the shaded dependencies:

- `Bouncy Castle <http://www.bouncycastle.org/>`_
- `Caffeine <https://github.com/ben-manes/caffeine>`_
- `completable-futures <https://github.com/spotify/completable-futures>`_
- `fastutil <http://fastutil.di.unimi.it/>`_
- `Guava <https://github.com/google/guava>`_
- `JCTools <https://jctools.github.io/JCTools/>`_
- `Reflections <https://github.com/ronmamo/reflections>`_

Please append the ``-shaded`` suffix to the artifact ID to use the shaded dependencies.
e.g. ``armeria`` to ``armeria-shaded`` and ``armeria-thrift`` to ``armeria-thrift-shaded``
