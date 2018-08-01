## How to contribute to Armeria project

First of all, thank you so much for taking your time to contribute! Armeria is not very different from any other open
source projects you are aware of. It will be amazing if you could help us by doing any of the following:

- File an issue in [the issue tracker](https://github.com/line/armeria/issues) to report bugs and propose new features and
  improvements.
- Ask a question by creating a new issue in [the issue tracker](https://github.com/line/armeria/issues).
  - Browse [the list of previously answered questions](https://github.com/line/armeria/issues?q=label%3Aquestion-answered).
- Contribute your work by sending [a pull request](https://github.com/line/armeria/pulls).

### Contributor license agreement

When you are sending a pull request and it's a non-trivial change beyond fixing typos, please sign 
[the ICLA (individual contributor license agreement)](https://cla-assistant.io/line/armeria). Please
[contact us](dl_oss_dev@linecorp.com) if you need the CCLA (corporate contributor license agreement).

### Code of conduct

We expect contributors to follow [our code of conduct](https://github.com/line/armeria/blob/master/CODE_OF_CONDUCT.md).

### Setting up your IDE

You can import Armeria into your IDE ([IntelliJ IDEA](https://www.jetbrains.com/idea/) or [Eclipse](https://www.eclipse.org/)) as a Gradle project.

- IntelliJ IDEA - See [Importing Project from Gradle Model](https://www.jetbrains.com/help/idea/2016.3/importing-project-from-gradle-model.html)
- Eclipse - Use [Buildship Gradle Integration](https://marketplace.eclipse.org/content/buildship-gradle-integration)

After importing the project, import the IDE settings as well.

#### IntelliJ IDEA

- [`settings.jar`](https://raw.githubusercontent.com/line/armeria/master/settings/intellij_idea/settings.jar) -
  See [Importing settings from a JAR archive](https://www.jetbrains.com/help/idea/2016.3/exporting-and-importing-settings.html#d2016665e55).
- Make sure to use 'LINE OSS' code style and inspection profile.
- Although optional, if you want to run Checkstyle from IDEA, install the
  [Checkstyle-IDEA plugin](https://github.com/jshiell/checkstyle-idea), import and activate
  the rule file at `settings/checkstyle/checkstyle.xml`.
  - It will ask for the value of the `checkstyleConfigDir` property.
    Set it to `<project root path>/settings/checkstyle`.
  - Set the 'Scan scope' to 'All sources (including tests)'.

#### Eclipse

- [`formatter.xml`](https://raw.githubusercontent.com/line/armeria/master/settings/eclipse/formatter.xml) -
  See [Code Formatter Preferences](https://help.eclipse.org/neon/index.jsp?topic=%2Forg.eclipse.jdt.doc.user%2Freference%2Fpreferences%2Fjava%2Fcodestyle%2Fref-preferences-formatter.htm).
- [`formatter.importorder`](https://raw.githubusercontent.com/line/armeria/master/settings/eclipse/formatter.importorder) -
  See [Organize Imports Preferences](https://help.eclipse.org/neon/index.jsp?topic=%2Forg.eclipse.jdt.doc.user%2Freference%2Fpreferences%2Fjava%2Fcodestyle%2Fref-preferences-organize-imports.htm).
- [`cleanup.xml`](https://raw.githubusercontent.com/line/armeria/master/settings/eclipse/cleanup.xml) -
  See [Clean Up Preferences](https://help.eclipse.org/neon/index.jsp?topic=%2Forg.eclipse.jdt.doc.user%2Freference%2Fpreferences%2Fjava%2Fcodestyle%2Fref-preferences-cleanup.htm).
- Configure [Java Save Actions Preferences](https://help.eclipse.org/neon/index.jsp?topic=%2Forg.eclipse.jdt.doc.user%2Freference%2Fpreferences%2Fjava%2Feditor%2Fref-preferences-save-actions.htm).
  <details><summary>Click here to see the screenshot.</summary>
    <img src="https://raw.githubusercontent.com/line/armeria/master/settings/eclipse/save_actions.png">
  </details>
- Although optional, if you want to run Checkstyle from Eclipse, install the
  [Eclipse Checkstyle Plugin](http://eclipse-cs.sourceforge.net/), import and activate
  the rule file at `settings/checkstyle/checkstyle.xml`.
  - Set the 'Type' to 'External Configuration File'.
  - Click the 'Additional properties...' button. A new dialog will show up.
  - Click the 'Find unresolved properties' button. It will find the `checkstyleConfigDir` property.
    Choose 'Yes' to add it. Set it to `<project root path>/settings/checkstyle`.

### Checklist for your pull request

Please use the following checklist to keep your contribution's quality high and
to save the reviewer's time.

#### Configure your IDE

- Make sure you are using 'LINE OSS' code style and inspection profile.
- Evaluate all warnings emitted by the 'LINE OSS' inspection profile.
  - Try to fix them all and use the `@SuppressWarnings` annotation if it's a false positive.

#### Always make the build pass

Make sure your change does not break the build.

- Run `./gradlew build site` locally.
- It is likely that you'll encounter some Checkstyle or Javadoc errors.
  Please fix them because otherwise the build will be broken.

#### Add copyright header

All source files must begin with the following copyright header:

```
Copyright $today.year LINE Corporation

LINE Corporation licenses this file to you under the Apache License,
version 2.0 (the "License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at:

  https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License.
```

#### Add Javadoc

All public classes and public or protected methods must have Javadoc,
except the classes under `com.linecorp.armeria.internal`.

#### Avoid redundancy

Avoid using redundant keywords. To list a few:

- `final` method modifier in a `final` class
- `static` or `public` modifier in an `interface`
- `public` method modifier in a package-local or private class
- `private` constructor modifier in an `enum`
- field access prefixed with `this.` where unnecessary

#### Use `public` only when necessary

The classes, methods and fields that are not meant to be used by a user should not be
public. Use the most restrictive modifier wherever possible, such as `private`,
package-local and `protected`, so that static analysis tools can find dead code easily.

#### Organize

Organize class members carefully for readability, using **top-down** approach.
Although there's no absolute rule of thumb, it's usually like:

- `static` fields
- `static` methods
- member fields
- constructors
- member methods
- utility methods (both `static` and member)
- inner classes

#### Check null

Do explicit `null`-check on the parameters of user-facing public methods.
Always use `Objects.requireNonNull(Object, String)` to do a `null`-check.

```java
import static java.util.Objects.requireNonNull;

public void setProperty(String name, String value) {
    // Great
    this.name = requireNonNull(name, "name");
    // Not great - we may not know which parameter is null exactly. 
    this.name = requireNonNull(name);
    // Not great - too verbose. NPE implies something's null already.
    this.name = requireNonNull(name, "name is null");
    // Not OK
    this.name = name
}
```

If you are using IntelliJ IDEA and you imported the `settings.jar` as explained
above, try the live template `rnn` and `rnna` which will save a lot of time.

##### Use `@Nullable`

Use `@Nullable` annotation for nullable parameters and return types.
Do not use `@Nonnull` annotation since we assume everything is non-null otherwise.

##### Avoid redundant null checks

Avoid unnecessary `null`-checks, including the hidden checks in `Objects.hashCode()` and `Objects.equals()`.

```java
public final class MyClass {
    private final String name;

    public MyClass(String name) {
        // We are sure 'name' is always non-null.
        this.name = requireNonNull(name, "name");
    }

    @Override
    public int hashCode() {
        // OK
        return name.hashCode();
        // Not OK
        return Objects.hash(name);
    }

    @Override
    public boolean equals(Object obj) {
        ... usual type check ...
        // OK
        return name.equals(((MyClass) obj).name);
        // Not OK
        return Objects.equals(name, ((MyClass) obj).name);
    }
}
```

#### Meaningful exception messages

When raising an exception, specify meaningful message which gives an explicit clue
about what went wrong.

```java
switch (fileType) {
    case TXT: ... break;
    case XML: ... break;
    default:
        // Note that the exception message contains the offending value
        // as well as the expected values.
        throw new IllegalStateException(
                "unsupported file type: " + fileType +
                 " (expected: " + FileType.TXT + " or " + FileType.XML + ')');
}
```

#### Validate

Do explicit validation on the parameters of user-facing public methods.
When raising an exception, always specify the detailed message in the following format:

```java
public void setValue(int value) {
    if (value < 0) {
        // Note that the exception message contains the offending value
        // as well as the expected value.
        throw new IllegalArgumentException("value: " + value + " (expected: >= 0)");
    }
}
```

#### Prefer JDK API

Prefer using plain JDK API when the same behavior can be achieved with the same
amount of code.

```java
// Prefer A (JDK) - less indirection
Map<String, String> map = new HashMap<>();   // A (JDK)
Map<String, String> map = Maps.newHashMap(); // B (Guava)

// Prefer B (Guava) - simpler yet more efficient
List<String> list = Collections.unmodifiableList(  // A (JDK)
        otherList.stream().filter(...).collect(Collectors.toList()));
List<String> list = otherList.stream().filter(...) // B (Guava)
        .collect(toImmutableList());
```

#### Prefer early-return style

Prefer 'early return' code style for readability.

```java
// Great
public void doSomething(String value) {
    if (value == null) {
        return;
    }

    // Do the actual job
}

// Not great
public void doSomething(String value) {
    if (value != null) {
        // Do the actual job
    }
}
```

However, when the 'normal' execution path is very simple, this may also look beautiful:

```java
public void doSomething(String value) {
    if (value != null) {
        return value.trim();
    } else {
        return null;
    }
}
```

#### Prefer `MoreObjects.toStringHelper()`

Prefer `MoreObjects.toStringHelper()` to hand-written `toString()` implementation.
However, consider writing hand-written or caching `toString()` implementation
in performance-sensitive places.  

#### Think aesthetics

Do not insert an empty line that hurts code aesthetics.

```java
// OK
if (...) {
    doSomething();
}

// Not OK
if (...) {
    doSomething();
                        // <-- Remove this extra line.
}
```

Similarly, do not use two or more consecutive empty lines.

```java
// OK
public void a() { ... }

public void b() { ... }

// Not OK
public void a() { ... }

                        // <-- Remove this extra line.
public void b() { ... }
```
