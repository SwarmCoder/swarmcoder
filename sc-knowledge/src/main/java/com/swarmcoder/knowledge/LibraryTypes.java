/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.ApiContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which types a library the project builds on really has — read from the library's own source
 * checkout, the reference root a project is configured with (for ZeroZ Stack projects, {@code
 * C:/work/zeroz4j}) — and, for a name it does not have, the real types with the nearest names.
 * Also answers the same question for the JDK the orchestrator runs on.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness runs 44/45, 2026-09-27 (DeepSeek V4 Flash, the Bookshelf demo on ZeroZ Stack, story
 * "Add, edit, and remove books"). The design's contract for the page task promised {@code public
 * com.zeroz4j.ui.ListView<Book> bookList}. There is no {@code com.zeroz4j.ui.ListView} in ZeroZ
 * Stack — the help desk said so when the worker asked, and named the real dynamic-list component,
 * {@code com.zeroz4j.ui.component.KeyedList}. The same contract also placed {@code TextField} and
 * {@code Button} in {@code com.zeroz4j.ui}; both really live in {@code com.zeroz4j.ui.component}.
 * The contract-delivery check compares a member's declared type, so no worker could ever deliver
 * that member; the page task burned its turns, one worker spending 290 seconds scanning jars for
 * {@code ListView}. Nothing between DESIGN and the worker ever asked whether a type a contract
 * names exists at all.
 *
 * <h2>What it will and will not judge</h2>
 *
 * <p>It judges a package only when a reference checkout is plainly the source of that part of the
 * namespace ({@link #covers}): the checkout declares a type in a package sharing at least the
 * first three segments with it ({@code com.zeroz4j.ui} is covered because the checkout has {@code
 * com.zeroz4j.ui.component}), or all of it when it is shorter. A package nothing indexed covers —
 * {@code org.teavm.jso.dom.html}, whose jar is on the build's classpath but whose source is not
 * checked out anywhere — is never judged: "we have no source for it" must never become "it does
 * not exist". There is no index of dependency jars in this codebase, so a library the project
 * uses without a reference checkout is simply not checked.
 *
 * <p><b>The version caveat, stated.</b> The reference checkout is not always the version the
 * project builds against (run 44: the project uses 0.8.0-SNAPSHOT, the checkout is 0.9.1). A type
 * that exists in one and not the other can be misjudged either way. The objections built on this
 * say "the reference source", never "the library", so an operator reading a park brief can tell.
 *
 * <p>Built from source text with {@link ProjectTypes}, so a file the scanner cannot read
 * contributes nothing; a checkout that cannot be walked yields an index that covers nothing.
 */
public final class LibraryTypes {

    private static final Logger log = LoggerFactory.getLogger(LibraryTypes.class);

    /** How many leading package segments two packages must share to be one library's namespace. */
    static final int NAMESPACE_SEGMENTS = 3;

    /** An index that covers nothing: every question it is asked is "not ours to judge". */
    public static final LibraryTypes NONE = new LibraryTypes(List.of());

    /**
     * A real type offered in place of one that does not exist.
     *
     * @param fullName its fully-qualified name
     * @param summary  the first sentence of its javadoc, or ""
     */
    public record Suggestion(String fullName, String summary) {}

    private record Indexed(String label, ProjectTypes types) {}

    private final List<Indexed> roots;
    /** A library-jar type's member names by its full name; an empty list is "not known". */
    private final java.util.function.Function<String, List<String>> jarMembers;

    private LibraryTypes(List<Indexed> roots) {
        this(roots, name -> List.of());
    }

    private LibraryTypes(List<Indexed> roots,
                         java.util.function.Function<String, List<String>> jarMembers) {
        this.roots = List.copyOf(roots);
        this.jarMembers = jarMembers;
    }

    /**
     * The same index, also able to say what a type that exists only in a library JAR declares
     * (2026-10-04) - asked of the Java language server, which reads the jars the project
     * compiles against. Before this a library with no reference checkout was "simply not
     * checked", and roles invented its methods.
     *
     * @param jarMembers a type's full name to the names of everything it declares or inherits;
     *                   an empty list when the type is not in any jar or nothing can say
     */
    public LibraryTypes withJarMembers(java.util.function.Function<String, List<String>> jarMembers) {
        return new LibraryTypes(roots, jarMembers == null ? name -> List.of() : jarMembers);
    }

    /**
     * The names of everything the library-jar type {@code fullName} declares or inherits; empty
     * when no jar is known to hold it. Never throws.
     */
    public List<String> jarMemberNames(String fullName) {
        try {
            List<String> names = jarMembers.apply(fullName);
            return names == null ? List.of() : names;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The members {@code contract} gives its type that the library-jar type of that name neither
     * declares nor inherits, by name. Empty when it has them all and when no jar is known to
     * hold the type. Constructors are not reported.
     */
    public List<String> membersNotInJar(ApiContract contract) {
        if (contract == null || !contract.namesAType()) {
            return List.of();
        }
        String name = contract.typeName().replace('$', '.').strip();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        List<String> real = jarMemberNames(name);
        if (real.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String member : contract.members()) {
            String declared = member == null ? "" : member.strip();
            int paren = declared.indexOf('(');
            String head = (paren < 0 ? declared : declared.substring(0, paren)).strip();
            int end = head.endsWith(";") ? head.length() - 1 : head.length();
            head = head.substring(0, end).strip();
            int equals = head.indexOf('=');
            head = (equals < 0 ? head : head.substring(0, equals)).strip();
            String memberName = JavaSourceFacts.lastToken(head);
            if (!memberName.isEmpty() && !memberName.equals(simple) && !real.contains(memberName)) {
                missing.add(memberName);
            }
        }
        return missing;
    }

    /**
     * An index over reference checkouts. Never the project's own checkout: a package the project
     * writes code into is one the plan may still add types to, and must never be judged as a
     * library's.
     */
    public static LibraryTypes of(List<Path> referenceRoots) {
        List<Indexed> indexed = new ArrayList<>();
        for (Path root : referenceRoots == null ? List.<Path>of() : referenceRoots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            ProjectTypes types = ProjectTypes.of(root);
            if (types.fullNames().isEmpty()) {
                continue;
            }
            String label = root.getFileName() == null ? root.toString() : root.getFileName().toString();
            indexed.add(new Indexed(label, types));
        }
        return new LibraryTypes(indexed);
    }

    /** True when at least one reference checkout was read. */
    public boolean any() {
        return !roots.isEmpty();
    }

    /**
     * True when a reference checkout is the source of this package's part of the namespace — see
     * the class javadoc. A package of fewer than two segments is never covered.
     */
    public boolean covers(String packageName) {
        return coveringRoot(packageName) != null;
    }

    /** The label of the reference checkout covering {@code packageName}, or null. */
    public String coveringRoot(String packageName) {
        if (packageName == null || segments(packageName).length < 2) {
            return null;
        }
        for (Indexed root : roots) {
            for (String known : root.types().packages()) {
                if (sameNamespace(packageName, known)) {
                    return root.label();
                }
            }
        }
        return null;
    }

    /**
     * True when some reference checkout declares {@code fullName}. A nested type may be named the
     * way a compiler prints it ({@code a.b.Outer.Inner}); it is found by its own simple name in
     * its package, which is how {@link ProjectTypes} indexes nested types.
     */
    public boolean declares(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return false;
        }
        String name = fullName.replace('$', '.').strip();
        String flattened = flattened(name);
        for (Indexed root : roots) {
            if (root.types().declares(name) || root.types().declares(flattened)) {
                return true;
            }
        }
        return false;
    }
    /**
     * The public surface of a type a reference checkout declares, as the source says it - see
     * {@link ProjectTypes#exposedMembers}. What a test author is shown when its test misuses a
     * library type that is not in the project's own tree. Empty when no reference checkout
     * declares it. Library-agnostic: nothing here names a library.
     */
    public List<JavaSourceFacts.Exposed> exposedMembers(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return List.of();
        }
        for (Indexed root : roots) {
            List<JavaSourceFacts.Exposed> members = root.types().exposedMembers(fullName);
            if (!members.isEmpty()) {
                return members;
            }
        }
        return List.of();
    }

    /**
     * The label of a reference checkout that has code under {@code packageName}'s first two
     * segments, or null. Looser than {@link #coveringRoot}, and only for a question that rule is
     * too strict for: a contract's OWN type in a package the library has nothing in yet ({@code
     * com.lib.widgets} beside the library's {@code com.lib.ui} and {@code com.lib.server}) is
     * still in the library's namespace and not the project's (live run 67, 2026-10-02). A caller
     * asks this only after the project's own packages have been ruled out.
     */
    public String looselyCoveringRoot(String packageName) {
        String[] subject = packageName == null ? new String[0] : segments(packageName);
        if (subject.length < 2) {
            return null;
        }
        for (Indexed root : roots) {
            for (String known : root.types().packages()) {
                String[] k = segments(known);
                if (k.length >= 2 && k[0].equals(subject[0]) && k[1].equals(subject[1])) {
                    return root.label();
                }
            }
        }
        return null;
    }

    /**
     * The members {@code contract} gives its type that the reference type of that name does not
     * declare. Empty when the type has them all, when no reference checkout declares it, and when
     * its declaration extends or implements anything: an inherited member is not in this file, so
     * "not declared here" would not mean "not there" ({@code getMessage()} on an exception).
     * Constructors are never reported, for the same reason of not being read reliably.
     */
    public List<String> membersNotOn(ApiContract contract) {
        if (contract == null || !contract.namesAType()) {
            return List.of();
        }
        String name = contract.typeName().replace('$', '.').strip();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        for (Indexed root : roots) {
            ProjectTypes types = root.types();
            String key = types.declares(name) ? name
                : types.declares(flattened(name)) ? flattened(name) : null;
            if (key == null) {
                continue;
            }
            if (hasSupertype(types.fileOf(key), simple)) {
                return List.of();
            }
            ApiContract probe = new ApiContract(contract.id(), contract.name(),
                contract.description(), contract.signatureSketch(), key, contract.members());
            List<ContractDelivery.Shortfall> shortfalls =
                ContractDelivery.shortfalls(types, List.of(probe));
            if (shortfalls.isEmpty() || shortfalls.get(0).missingType()) {
                return List.of();
            }
            return shortfalls.get(0).missingMembers().stream()
                .filter(m -> !m.strip().matches("(?:public\\s+)?" + Pattern.quote(simple) + "\\s*\\(.*"))
                .toList();
        }
        return List.of();
    }

    /** True when {@code file} declares {@code simple} with an extends or implements clause, or cannot be read. */
    private static boolean hasSupertype(Path file, String simple) {
        if (file == null) {
            return true;
        }
        try {
            String source = Files.readString(file);
            return Pattern.compile("\\b(?:class|interface|record|enum)\\s+" + Pattern.quote(simple)
                + "\\b[^{;]*\\b(?:extends|implements)\\b").matcher(source).find();
        } catch (java.io.IOException | RuntimeException e) {
            return true;
        }
    }

    /**
     * The fully-qualified names of reference types with this simple name, at most {@code max} -
     * how an offending source line that says {@code Type.method(..)} finds its type.
     */
    public List<String> fullNamesOf(String simpleName, int max) {
        List<String> found = new ArrayList<>();
        if (simpleName == null || simpleName.isBlank() || max <= 0) {
            return found;
        }
        String simple = simpleName.strip();
        for (Indexed root : roots) {
            if (!root.types().hasSimpleName(simple)) {
                continue;
            }
            for (String full : root.types().fullNames()) {
                if ((full.equals(simple) || full.endsWith("." + simple)) && !found.contains(full)) {
                    found.add(full);
                    if (found.size() >= max) {
                        return found;
                    }
                }
            }
        }
        return found;
    }


    /**
     * The real types, in the part of the namespace {@code fullName}'s package belongs to, whose
     * names are nearest to it — at most {@code max}, main sources only (a test class is never on
     * anybody's classpath).
     *
     * <p>A type of exactly the same simple name comes first and alone: run 44's {@code
     * com.zeroz4j.ui.TextField} is {@code com.zeroz4j.ui.component.TextField}, and there is nothing
     * to choose. Otherwise the candidates share at least one word of the camel-cased name ({@code
     * ListView} → {@code KeyedList}, {@code DiffView}, {@code MarkdownView}); a candidate whose
     * javadoc also uses one of those words ranks higher, because a name alone says little about
     * what a type is for ({@code KeyedList}'s javadoc calls it "the standard way every dynamic
     * list … renders"; {@code DiffView}'s never mentions a list). Nothing sharing a word means nothing is suggested — a list of
     * unrelated types would read like advice and is not.
     */
    public List<Suggestion> nearest(String fullName, int max) {
        if (fullName == null || fullName.isBlank() || max <= 0) {
            return List.of();
        }
        String name = fullName.replace('$', '.').strip();
        String pkg = packageOf(name);
        String simple = name.substring(name.lastIndexOf('.') + 1);
        List<String> exact = new ArrayList<>();
        List<String> related = new ArrayList<>();
        Set<String> wanted = words(simple);
        for (Indexed root : roots) {
            for (String candidate : root.types().fullNames()) {
                if (!sameNamespace(pkg, packageOf(candidate)) || isTestSource(root, candidate)) {
                    continue;
                }
                String candidateSimple = candidate.substring(candidate.lastIndexOf('.') + 1);
                if (candidateSimple.equals(simple)) {
                    exact.add(candidate);
                } else if (overlap(wanted, words(candidateSimple)) > 0) {
                    related.add(candidate);
                }
            }
        }
        if (!exact.isEmpty()) {
            return exact.stream().distinct().sorted().limit(max)
                .map(c -> new Suggestion(c, firstSentence(javadocOf(c)))).toList();
        }
        record Scored(String name, String summary, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (String candidate : new LinkedHashSet<>(related)) {
            String candidateSimple = candidate.substring(candidate.lastIndexOf('.') + 1);
            String javadoc = javadocOf(candidate);
            int score = 2 * overlap(wanted, words(candidateSimple))
                + (mentionsAny(javadoc, wanted) ? 1 : 0);
            scored.add(new Scored(candidate, firstSentence(javadoc), score));
        }
        return scored.stream()
            .sorted(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.name().substring(s.name().lastIndexOf('.') + 1))
                .thenComparing(Scored::name))
            .limit(max)
            .map(s -> new Suggestion(s.name(), s.summary()))
            .toList();
    }

    // --- which artifact declares a package ---------------------------------------------------------

    private static final Pattern POM_PARENT =
        Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);
    private static final Pattern POM_GROUP =
        Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern POM_ARTIFACT =
        Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    /**
     * The Maven coordinate ({@code groupId:artifactId}) of the reference module whose source
     * declares {@code packageName}, or empty when the reference checkouts do not hold the package
     * or the module's {@code pom.xml} cannot be read. Cheap on purpose: the nearest {@code pom.xml}
     * above the file that declares one of {@code types} (else any type of the package), nothing
     * resolved, no version.
     *
     * @param types simple names to look for first; may be empty
     */
    public java.util.Optional<String> declaringArtifact(String packageName, List<String> types) {
        if (packageName == null || packageName.isBlank()) {
            return java.util.Optional.empty();
        }
        String pkg = packageName.strip();
        for (Indexed root : roots) {
            Path file = null;
            for (String simple : types == null ? List.<String>of() : types) {
                file = root.types().fileOf(pkg + "." + simple);
                if (file != null) {
                    break;
                }
            }
            if (file == null) {
                for (String full : root.types().fullNames()) {
                    if (packageOf(full).equals(pkg) && !full.substring(pkg.length() + 1).contains(".")) {
                        file = root.types().fileOf(full);
                        if (file != null) {
                            break;
                        }
                    }
                }
            }
            if (file == null) {
                continue;
            }
            for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
                Path pom = dir.resolve("pom.xml");
                if (!Files.isRegularFile(pom)) {
                    continue;
                }
                try {
                    String text = Files.readString(pom);
                    Matcher parent = POM_PARENT.matcher(text);
                    String parentGroup = null;
                    if (parent.find()) {
                        Matcher g = POM_GROUP.matcher(parent.group());
                        parentGroup = g.find() ? g.group(1) : null;
                        text = text.substring(0, parent.start()) + text.substring(parent.end());
                    }
                    int cut = text.length();
                    for (String tag : List.of("<dependencies", "<dependencyManagement", "<build>",
                            "<profiles>", "<properties>", "<modules>")) {
                        int at = text.indexOf(tag);
                        if (at >= 0) {
                            cut = Math.min(cut, at);
                        }
                    }
                    text = text.substring(0, cut);
                    Matcher artifact = POM_ARTIFACT.matcher(text);
                    if (!artifact.find()) {
                        return java.util.Optional.empty();
                    }
                    Matcher group = POM_GROUP.matcher(text);
                    String groupId = group.find() ? group.group(1) : parentGroup;
                    return java.util.Optional.of(
                        (groupId == null ? "" : groupId + ":") + artifact.group(1));
                } catch (IOException | RuntimeException e) {
                    return java.util.Optional.empty();
                }
            }
        }
        return java.util.Optional.empty();
    }

    // --- the JDK -----------------------------------------------------------------------------------

    private static volatile Set<String> jdkPackages;

    /**
     * True when {@code packageName} belongs to the JDK the orchestrator runs on — any package of a
     * {@code java.*} or {@code jdk.*} module of the boot layer. The application's own classes are
     * never in the boot layer (it runs on the class path), so nothing of SwarmCoder's own counts.
     */
    public static boolean isJdkPackage(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        Set<String> known = jdkPackages;
        if (known == null) {
            Set<String> read = new HashSet<>();
            try {
                for (Module module : ModuleLayer.boot().modules()) {
                    ModuleDescriptor descriptor = module.getDescriptor();
                    String moduleName = descriptor == null ? module.getName() : descriptor.name();
                    if (moduleName != null
                            && (moduleName.startsWith("java.") || moduleName.startsWith("jdk."))) {
                        read.addAll(module.getPackages());
                    }
                }
            } catch (RuntimeException e) {
                log.debug("could not read the JDK's packages: {}", e.toString());
            }
            jdkPackages = known = Set.copyOf(read);
        }
        return known.contains(packageName.strip());
    }

    /**
     * True when the JDK declares {@code fullName}, a nested type included ({@code
     * java.util.Map.Entry}). Loads nothing: the class is looked up without being initialised.
     */
    public static boolean jdkDeclares(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return false;
        }
        String name = fullName.replace('$', '.').strip();
        String[] parts = segments(name);
        int firstType = 0;
        while (firstType < parts.length && !startsUpper(parts[firstType])) {
            firstType++;
        }
        if (firstType == 0 || firstType >= parts.length) {
            return false;
        }
        StringBuilder binary = new StringBuilder(String.join(".",
            java.util.Arrays.copyOfRange(parts, 0, firstType + 1)));
        for (int i = firstType + 1; i < parts.length; i++) {
            binary.append('$').append(parts[i]);
        }
        try {
            Class.forName(binary.toString(), false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * The package part of a qualified type name: the segments before the first one that starts
     * with a capital letter — so a nested {@code a.b.Outer.Inner} is in {@code a.b}.
     */
    public static String packageOf(String qualifiedTypeName) {
        StringBuilder pkg = new StringBuilder();
        for (String part : segments(qualifiedTypeName)) {
            if (startsUpper(part)) {
                break;
            }
            pkg.append(pkg.isEmpty() ? "" : ".").append(part);
        }
        return pkg.toString();
    }

    /**
     * True when {@code subject} lies in the part of the namespace {@code known} belongs to: they
     * share {@code subject}'s first {@link #NAMESPACE_SEGMENTS} segments, or all of {@code
     * subject} when it is shorter than that. Deliberately one-sided: a checkout that declares
     * {@code com.zeroz4j.ui.component} speaks for {@code com.zeroz4j.ui}, but a checkout that
     * happens to declare a type straight in {@code com.google} does not speak for every
     * {@code com.google.*} library there is. A subject of fewer than two segments is in nobody's
     * namespace. Public so the design check applies the very same rule to the project's own
     * packages.
     */
    public static boolean sameNamespace(String subject, String known) {
        if (subject == null || known == null || subject.isBlank() || known.isBlank()) {
            return false;
        }
        String[] x = segments(subject);
        String[] y = segments(known);
        int needed = Math.min(NAMESPACE_SEGMENTS, x.length);
        if (needed < 2 || y.length < needed) {
            return false;
        }
        for (int i = 0; i < needed; i++) {
            if (!x[i].equals(y[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The javadoc on the declaration of {@code fullName}, as plain text: {@code {@code X}} and
     * {@code {@link X}} become X, HTML tags go, and a generic like {@code Signal<List<T>>} is
     * kept — {@link JavaOutline}'s own first-sentence reader strips it as a tag, which on run 44's
     * {@code KeyedList} removes the one word saying it is about lists. "" when there is none.
     */
    private String javadocOf(String fullName) {
        String simple = fullName.substring(fullName.lastIndexOf('.') + 1);
        for (Indexed root : roots) {
            Path file = root.types().fileOf(fullName);
            if (file == null) {
                continue;
            }
            try {
                Matcher m = Pattern.compile("/\\*\\*((?:(?!\\*/).)*)\\*/\\s*"
                    + "(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
                    + "(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|"
                    + "strictfp)\\s+)*(?:class|interface|enum|record|@interface)\\s+"
                    + Pattern.quote(simple) + "\\b", Pattern.DOTALL).matcher(Files.readString(file));
                if (!m.find()) {
                    return "";
                }
                StringBuilder text = new StringBuilder();
                for (String line : m.group(1).split("\n")) {
                    String stripped = line.strip();
                    if (stripped.startsWith("*")) {
                        stripped = stripped.substring(1).strip();
                    }
                    if (stripped.startsWith("@")) {
                        break; // the tag block
                    }
                    text.append(stripped).append(' ');
                }
                return text.toString()
                    .replaceAll("\\{@\\w+\\s+#?([^{}]*)\\}", "$1")
                    .replaceAll("</?[a-z][a-z0-9]*[^<>]*>", "")
                    .replaceAll("\\s+", " ").strip();
            } catch (IOException | RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    /** Up to the first full stop followed by a space, trimmed to a line's worth. */
    private static String firstSentence(String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return "";
        }
        int stop = javadoc.indexOf(". ");
        String sentence = stop < 0 ? javadoc : javadoc.substring(0, stop + 1);
        return sentence.length() > 160 ? sentence.substring(0, 157).strip() + "…" : sentence;
    }

    private static boolean isTestSource(Indexed root, String fullName) {
        Path file = root.types().fileOf(fullName);
        return file != null && file.toString().replace('\\', '/').contains("/src/test/");
    }

    private static String flattened(String name) {
        String pkg = packageOf(name);
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    private static final Pattern WORD = Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z0-9]+");

    /** The lower-cased words of a camel-cased name: {@code ListView} → list, view. */
    static Set<String> words(String simpleName) {
        Set<String> words = new LinkedHashSet<>();
        Matcher m = WORD.matcher(simpleName == null ? "" : simpleName);
        while (m.find()) {
            String word = m.group().toLowerCase(Locale.ROOT);
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String word : a) {
            if (b.contains(word)) {
                n++;
            }
        }
        return n;
    }

    private static boolean mentionsAny(String text, Set<String> words) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (Pattern.compile("\\b" + Pattern.quote(word) + "s?\\b").matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private static String[] segments(String dotted) {
        return dotted == null || dotted.isBlank() ? new String[0] : dotted.strip().split("\\.");
    }

    private static boolean startsUpper(String part) {
        return !part.isEmpty() && Character.isUpperCase(part.charAt(0));
    }
}
