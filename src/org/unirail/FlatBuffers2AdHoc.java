package org.unirail;

import org.unirail.adhoc.AdHocWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.brush;
import static org.unirail.adhoc.AdHocWriter.doc;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.str;

/**
 * FlatBuffers schema (.fbs) → AdHoc protocol description (.cs) converter.
 *
 * <p>Usage: <code>java -cp out org.unirail.FlatBuffers2AdHoc &lt;file.fbs | folder&gt; [output folder]</code>.
 * Every .fbs found (recursively) is a top-level schema; its <code>include</code>s are merged into it and one
 * self-contained <code>&lt;name&gt;.cs</code> is written per schema (output defaults to <code>&lt;cwd&gt;/AdHoc</code>).
 *
 * <p>Mapping summary (details in README.md):
 * <ul>
 *   <li><code>namespace a.b.c;</code> → nested non-transmittable <code>struct a { struct b { struct c { … } } }</code>
 *       containers, so equally named types of different namespaces coexist;</li>
 *   <li><code>table</code> / <code>struct</code> → <code>class</code> pack; <code>union</code> → pack with one optional
 *       field per alternative; <code>enum</code> → <code>enum</code> (<code>bit_flags</code> → <code>[Flags]</code>
 *       with values 1&lt;&lt;bit);</li>
 *   <li>vectors → <code>T[,,]</code>, <code>[ubyte]</code> → <code>Binary[,,]</code>, fixed arrays → <code>[D(N)] T[]</code>;</li>
 *   <li>field metadata → custom attributes (<code>[Default]</code>, <code>[FieldId]</code>, <code>[Deprecated]</code>,
 *       <code>[Required]</code>, <code>[SortKey]</code>, <code>[ForceAlign]</code>, <code>[User]</code>);</li>
 *   <li><code>rpc_service</code> methods → RPC shorthand <code>(L____________, Response) name(Request req);</code>;</li>
 *   <li>all tables not used by an RPC → one bidirectional non-transitional state.</li>
 * </ul>
 */
public class FlatBuffers2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.FlatBuffers2AdHoc <file.fbs | folder> [output folder]");
			System.out.println("       output folder defaults to <current dir>/AdHoc; every .fbs under a folder is converted");
			return;
		}
		Path src = Paths.get(args[0]).toAbsolutePath().normalize();
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		Path root = Files.isDirectory(src) ? src : src.getParent();

		List<Path> inputs;
		if (Files.isDirectory(src))
			try (Stream<Path> w = Files.walk(src)) {
				inputs = w.filter(p -> p.toString().endsWith(".fbs")).map(Path::normalize).sorted().collect(Collectors.toList());
			}
		else inputs = Arrays.asList(src);
		if (inputs.isEmpty()) {
			System.err.println("No .fbs files found in `" + src.toAbsolutePath() + "`.");
			System.exit(1);
			return;
		}
		Files.createDirectories(dst);

		Set<String> projectNames = new HashSet<>(RESERVED);
		int failed = 0;
		for (Path in : inputs)
			try {
				Schema schema = Schema.load(in, root);
				String base = in.getFileName().toString();
				base = base.substring(0, base.length() - ".fbs".length());
				String project = ident(base);
				if (projectNames.contains(project)) { // a name clash with a Meta type or another schema
					String rel = root.relativize(in.toAbsolutePath().normalize()).toString().replace('\\', '/');
					project = ident(rel.substring(0, rel.length() - ".fbs".length()).replace('/', '_'));
					if (projectNames.contains(project)) project = project + "_fbs";
				}
				projectNames.add(project);
				Path out = dst.resolve(project + ".cs");
				Files.write(out, new Emitter(schema, project).emit().getBytes(StandardCharsets.UTF_8));
				System.out.printf("%-48s -> %s  (%d types, %d rpc methods, sources: %s)%n",
						root.relativize(in.toAbsolutePath().normalize()), out.getFileName(), schema.decls.size(),
						schema.services.stream().mapToInt(s -> s.methods.size()).sum(), String.join(" ", schema.sources));
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + in + ": " + e.getMessage());
				e.printStackTrace();
			}
		if (0 < failed) System.exit(2);
	}

	/**
	 * Arrow's temporal tables are logical-TYPE descriptors, not time values: {@code Timestamp} carries a
	 * {@code unit} and a {@code timezone}, never an instant. Mapping them to AdHoc {@code DateTime} would be a
	 * category error, so the converter only points at the AdHoc type the described VALUES would use.
	 * Keyed by fully qualified FlatBuffers name.
	 */
	static final Map<String, String> TEMPORAL_DESCRIPTORS = new LinkedHashMap<>();

	static {
		String arrow = "org.apache.arrow.flatbuf.";
		TEMPORAL_DESCRIPTORS.put(arrow + "Timestamp", "a wall-clock instant is `DateTime`, or `class T : DateTimeDef { min; max; precision; }` when the epoch and resolution are pinned.");
		TEMPORAL_DESCRIPTORS.put(arrow + "Date", "a date is `class D : DateTimeDef { min; max; precision => TimeSpan.FromDays(1); }`.");
		TEMPORAL_DESCRIPTORS.put(arrow + "Time", "a time of day is `class T : TimeSpanDef { interval => TimeSpan.FromDays(1); precision; }`.");
		TEMPORAL_DESCRIPTORS.put(arrow + "Duration", "an elapsed duration is `class D : Duration { max; precision; }`.");
		TEMPORAL_DESCRIPTORS.put(arrow + "Interval", "a calendar interval has no AdHoc counterpart; keep month / day / nanosecond as separate fields.");
	}

	/** Names a schema type must not take inside the project interface (they would shadow org.unirail.Meta types or ours). */
	static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
			"Binary", "Set", "Map", "Host", "Actor", "End", "Close", "Empty", "Stream", "File", "Modify", "SwapHosts",
			"Connects", "VirtuallyConnects", "Offline", "DateTimeDef", "TimeSpanDef", "Duration", "longJS", "ulongJS",
			"All", "InTS", "InCS", "InJAVA", "InCPP", "InGO", "InRS", "FieldsInjectInto", "HeaderFor", "X", "Resumable",
			"Attribute", "Client", "Server", "Connection", "FlatBufferFile", "Exchange"));

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	static final class TypeRef {
		String base;          // scalar keyword, "string", or a (possibly qualified) type name as written
		boolean vector;       // [T]
		int fixedLen = -1;    // [T:N] (structs only)

		boolean isScalar() { return SCALARS.containsKey(base); }
		boolean isString() { return base.equals("string"); }
	}

	static final class Field {
		String name, doc, dflt; // dflt as written ("100", "Blue", "null", "nan", "+inf", "true")
		TypeRef type;
		final Map<String, String> meta = new LinkedHashMap<>();
	}

	static final class EnumVal { String name, doc; Long value; }

	static final class UnionVal { String alias, type; String doc; }

	static final class Decl {
		String kind;          // table | struct | enum | union
		String ns = "", name, doc, origin, baseType;
		boolean bitFlags;
		final List<Field> fields = new ArrayList<>();
		final List<EnumVal> values = new ArrayList<>();
		final List<UnionVal> members = new ArrayList<>();
		final Map<String, String> meta = new LinkedHashMap<>();

		String full() { return ns.isEmpty() ? name : ns + "." + name; }
	}

	static final class RpcMethod {
		String name, doc, request, response, ns;
		final Map<String, String> meta = new LinkedHashMap<>();
	}

	static final class Service {
		String ns = "", name, doc;
		final List<RpcMethod> methods = new ArrayList<>();
	}

	static final class Schema {
		final LinkedHashMap<String, Decl> decls = new LinkedHashMap<>(); // full name → declaration, first wins
		final List<Service> services = new ArrayList<>();
		final Set<String> userAttributes = new LinkedHashSet<>();
		final List<String> sources = new ArrayList<>();
		String rootType, fileIdentifier, fileExtension;
		private final Set<Path> loaded = new HashSet<>();
		private Path root;

		static Schema load(Path fbs, Path root) throws IOException {
			Schema s = new Schema();
			s.root = root.toAbsolutePath().normalize();
			s.include(fbs.toAbsolutePath().normalize(), null);
			return s;
		}

		private void include(Path file, Path includedFrom) throws IOException {
			if (!loaded.add(file)) return;
			if (!Files.exists(file)) {
				System.err.println("WARNING " + (includedFrom == null ? "" : includedFrom.getFileName() + ": ") + "included file `" + file + "` not found, skipped.");
				return;
			}
			String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			Parser p = new Parser(text, file.getFileName().toString());
			// Resolve includes first so that an including file's own declarations win on duplicates only when
			// they were declared first in FlatBuffers terms (flatc forbids duplicates anyway).
			for (String inc : p.includes) include(resolveInclude(file, inc), file);
			sources.add(root.relativize(file).toString().replace('\\', '/'));
			for (Decl d : p.decls)
				if (decls.putIfAbsent(d.full(), d) != null && !decls.get(d.full()).origin.equals(d.origin))
					System.err.println("WARNING " + d.origin + ": type `" + d.full() + "` already defined in " + decls.get(d.full()).origin + ", ignored.");
			services.addAll(p.services);
			userAttributes.addAll(p.userAttributes);
			if (rootType == null) rootType = p.rootType;
			if (fileIdentifier == null) fileIdentifier = p.fileIdentifier;
			if (fileExtension == null) fileExtension = p.fileExtension;
		}

		/**
		 * Include lookup: relative to the including file, then to every ancestor up to the input root, and finally
		 * (standing in for flatc's {@code -I} include paths) any file under the root whose path ends with the include.
		 */
		private Path resolveInclude(Path from, String inc) throws IOException {
			Path dir = from.getParent();
			while (dir != null) {
				Path p = dir.resolve(inc).normalize();
				if (Files.exists(p)) return p;
				if (dir.equals(root)) break;
				dir = dir.getParent();
			}
			String suffix = inc.replace('\\', '/');
			try (Stream<Path> w = Files.walk(root)) {
				List<Path> hits = w.filter(p -> p.toString().replace('\\', '/').endsWith("/" + suffix)).sorted().collect(Collectors.toList());
				if (hits.size() == 1) return hits.get(0).normalize();
				if (1 < hits.size()) System.err.println("WARNING " + from.getFileName() + ": include `" + inc + "` is ambiguous under " + root + ", using " + hits.get(0));
				if (!hits.isEmpty()) return hits.get(0).normalize();
			}
			return root.resolve(inc).normalize();
		}
	}

	// ═══════════════════════════════════════════ scalar types ═══════════════════════════════════════════

	static final Map<String, String> SCALARS = new HashMap<>();

	static {
		String[][] t = {
				{"bool", "bool"}, {"byte", "sbyte"}, {"int8", "sbyte"}, {"ubyte", "byte"}, {"uint8", "byte"},
				{"short", "short"}, {"int16", "short"}, {"ushort", "ushort"}, {"uint16", "ushort"},
				{"int", "int"}, {"int32", "int"}, {"uint", "uint"}, {"uint32", "uint"},
				{"long", "long"}, {"int64", "long"}, {"ulong", "ulong"}, {"uint64", "ulong"},
				{"float", "float"}, {"float32", "float"}, {"double", "double"}, {"float64", "double"}};
		for (String[] x : t) SCALARS.put(x[0], x[1]);
	}

	// ═══════════════════════════════════════════ tokenizer ═══════════════════════════════════════════

	enum Kind { IDENT, NUMBER, STRING, PUNCT, EOF }

	static final class Token {
		Kind kind;
		String text;
		String doc; // `///` (and directly preceding `//`) comment text attached to this token
		int line;
	}

	static List<Token> tokenize(String s, String origin) {
		List<Token> out = new ArrayList<>();
		StringBuilder doc = new StringBuilder();     // /// doc comments, kept until the next token
		StringBuilder plain = new StringBuilder();   // // comments, kept only while no blank line intervenes
		int i = 0, n = s.length(), line = 1, newlines = 0;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '\n') { line++; newlines++; if (1 < newlines) plain.setLength(0); i++; continue; }
			if (Character.isWhitespace(c)) { i++; continue; }
			if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
				boolean isDoc = i + 2 < n && s.charAt(i + 2) == '/';
				int e = s.indexOf('\n', i);
				if (e < 0) e = n;
				String body = s.substring(i + (isDoc ? 3 : 2), e).trim();
				if (isDoc) doc.append(body).append('\n');
				else if (newlines <= 1 && !out.isEmpty() && out.get(out.size() - 1).line == line) { /* trailing comment: attach to previous token */
					Token prev = out.get(out.size() - 1);
					prev.doc = (prev.doc == null ? "" : prev.doc) + body + "\n";
				} else plain.append(body).append('\n');
				newlines = 0;
				i = e;
				continue;
			}
			if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
				int e = s.indexOf("*/", i + 2);
				if (e < 0) e = n - 2;
				for (int k = i; k < e; k++) if (s.charAt(k) == '\n') line++;
				i = e + 2;
				continue;
			}
			Token t = new Token();
			t.line = line;
			if (0 < doc.length() || 0 < plain.length()) {
				t.doc = (plain.toString() + doc).trim();
				doc.setLength(0);
				plain.setLength(0);
			}
			newlines = 0;
			if (c == '"') {
				int j = i + 1;
				StringBuilder sb = new StringBuilder();
				while (j < n && s.charAt(j) != '"') {
					if (s.charAt(j) == '\\' && j + 1 < n) j++;
					sb.append(s.charAt(j++));
				}
				t.kind = Kind.STRING;
				t.text = sb.toString();
				i = j + 1;
			} else if (Character.isLetter(c) || c == '_') {
				int j = i;
				while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
				t.kind = Kind.IDENT;
				t.text = s.substring(i, j);
				i = j;
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
				int j = i;
				if (c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
					j = i + 2;
					while (j < n && Character.digit(s.charAt(j), 16) >= 0) j++;
				} else
					while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == 'e' || s.charAt(j) == 'E'
							|| ((s.charAt(j) == '+' || s.charAt(j) == '-') && (s.charAt(j - 1) == 'e' || s.charAt(j - 1) == 'E')))) j++;
				t.kind = Kind.NUMBER;
				t.text = s.substring(i, j);
				i = j;
			} else {
				t.kind = Kind.PUNCT;
				t.text = String.valueOf(c);
				i++;
			}
			out.add(t);
		}
		Token eof = new Token();
		eof.kind = Kind.EOF;
		eof.text = "<EOF>";
		eof.line = line;
		out.add(eof);
		return out;
	}

	// ═══════════════════════════════════════════ parser ═══════════════════════════════════════════

	static final class Parser {
		final List<Token> toks;
		final String origin;
		int p;
		String ns = "";
		final List<String> includes = new ArrayList<>();
		final List<Decl> decls = new ArrayList<>();
		final List<Service> services = new ArrayList<>();
		final Set<String> userAttributes = new LinkedHashSet<>();
		String rootType, fileIdentifier, fileExtension;

		Parser(String text, String origin) {
			this.origin = origin;
			toks = tokenize(text, origin);
			schema();
		}

		Token peek() { return toks.get(p); }

		Token next() { return toks.get(p++); }

		boolean is(String text) { return peek().text.equals(text) && peek().kind != Kind.STRING; }

		boolean accept(String text) {
			if (!is(text)) return false;
			p++;
			return true;
		}

		Token expect(String text) {
			if (!is(text)) throw err("`" + text + "` expected but got `" + peek().text + "`");
			return next();
		}

		String ident() {
			Token t = next();
			if (t.kind != Kind.IDENT) throw err("identifier expected but got `" + t.text + "`");
			return t.text;
		}

		String qualified() {
			StringBuilder sb = new StringBuilder(ident());
			while (accept(".")) sb.append('.').append(ident());
			return sb.toString();
		}

		IllegalStateException err(String msg) { return new IllegalStateException(origin + ":" + peek().line + ": " + msg); }

		void schema() {
			while (peek().kind != Kind.EOF) {
				Token t = peek();
				switch (t.text) {
					case "include": next(); includes.add(next().text); expect(";"); break;
					case "namespace": next(); ns = qualified(); expect(";"); break;
					case "attribute": next(); userAttributes.add(next().text); expect(";"); break;
					case "table":
					case "struct": typeDecl(); break;
					case "enum": enumDecl(); break;
					case "union": unionDecl(); break;
					case "root_type": next(); rootType = qualified(); expect(";"); break;
					case "file_identifier": next(); fileIdentifier = next().text; expect(";"); break;
					case "file_extension": next(); fileExtension = next().text; expect(";"); break;
					case "rpc_service": rpcDecl(); break;
					case "{": skipJsonObject(); break; // a JSON object (flatc accepts data in schema files)
					default: throw err("unexpected `" + t.text + "`");
				}
			}
		}

		void skipJsonObject() {
			int depth = 0;
			do {
				Token t = next();
				if (t.text.equals("{")) depth++;
				else if (t.text.equals("}")) depth--;
				if (t.kind == Kind.EOF) throw err("unterminated JSON object");
			} while (0 < depth);
		}

		void typeDecl() {
			Token kw = next();
			Decl d = new Decl();
			d.kind = kw.text;
			d.doc = kw.doc;
			d.ns = ns;
			d.origin = origin;
			d.name = ident();
			metadata(d.meta);
			expect("{");
			while (!accept("}")) {
				Field f = new Field();
				Token nameTok = next();
				if (nameTok.kind != Kind.IDENT) throw err("field name expected but got `" + nameTok.text + "`");
				f.name = nameTok.text;
				f.doc = nameTok.doc;
				expect(":");
				f.type = type();
				if (accept("=")) f.dflt = scalarValue();
				metadata(f.meta);
				Token semi = expect(";");
				if (semi.doc != null) f.doc = (f.doc == null ? "" : f.doc + "\n") + semi.doc; // trailing // comment
				d.fields.add(f);
			}
			decls.add(d);
		}

		TypeRef type() {
			TypeRef t = new TypeRef();
			if (accept("[")) {
				t.vector = true;
				t.base = qualified();
				if (accept(":")) {
					t.vector = false;
					t.fixedLen = (int) parseLong(next().text);
				}
				expect("]");
			} else t.base = qualified();
			return t;
		}

		/** A default value: number (optionally signed), identifier (enum member, true/false/null/nan/inf/infinity), or string. */
		String scalarValue() {
			StringBuilder sb = new StringBuilder();
			if (is("-") || is("+")) sb.append(next().text);
			Token t = next();
			if (t.kind == Kind.STRING) return t.text;
			sb.append(t.text);
			return sb.toString();
		}

		void metadata(Map<String, String> into) {
			if (!accept("(")) return;
			while (!accept(")")) {
				String key = next().text; // ident (or string in odd schemas)
				String value = "";
				if (accept(":")) {
					Token v = next();
					value = v.text;
					if (v.kind == Kind.PUNCT && (v.text.equals("-") || v.text.equals("+"))) value += next().text;
				}
				into.put(key, value);
				accept(",");
			}
		}

		void enumDecl() {
			Token kw = next();
			Decl d = new Decl();
			d.kind = "enum";
			d.doc = kw.doc;
			d.ns = ns;
			d.origin = origin;
			d.name = ident();
			if (accept(":")) d.baseType = ident();
			else d.baseType = "int"; // FlatBuffers requires the type; be lenient
			metadata(d.meta);
			d.bitFlags = d.meta.containsKey("bit_flags");
			expect("{");
			long next = 0;
			while (!accept("}")) {
				EnumVal v = new EnumVal();
				Token nameTok = next();
				if (nameTok.kind != Kind.IDENT) throw err("enum value name expected but got `" + nameTok.text + "`");
				v.name = nameTok.text;
				v.doc = nameTok.doc;
				if (accept("=")) {
					StringBuilder sb = new StringBuilder();
					if (is("-") || is("+")) sb.append(next().text);
					sb.append(next().text);
					next = parseLong(sb.toString());
				}
				v.value = next++;
				metadata(new LinkedHashMap<>());
				Token comma = peek();
				if (accept(",") && comma.doc != null) v.doc = (v.doc == null ? "" : v.doc + "\n") + comma.doc;
				d.values.add(v);
			}
			if (d.bitFlags) for (EnumVal v : d.values) v.value = 1L << v.value; // bit_flags values are bit indexes
			decls.add(d);
		}

		void unionDecl() {
			Token kw = next();
			Decl d = new Decl();
			d.kind = "union";
			d.doc = kw.doc;
			d.ns = ns;
			d.origin = origin;
			d.name = ident();
			metadata(d.meta);
			expect("{");
			while (!accept("}")) {
				UnionVal u = new UnionVal();
				Token first = peek();
				String q = qualified();
				if (accept(":")) {
					u.alias = q;
					u.type = qualified();
				} else {
					u.type = q;
					u.alias = q.substring(q.lastIndexOf('.') + 1);
				}
				u.doc = first.doc;
				metadata(new LinkedHashMap<>());
				Token comma = peek();
				if (accept(",") && comma.doc != null) u.doc = (u.doc == null ? "" : u.doc + "\n") + comma.doc;
				d.members.add(u);
			}
			decls.add(d);
		}

		void rpcDecl() {
			Token kw = next();
			Service s = new Service();
			s.doc = kw.doc;
			s.ns = ns;
			s.name = ident();
			expect("{");
			while (!accept("}")) {
				RpcMethod m = new RpcMethod();
				Token nameTok = next();
				if (nameTok.kind != Kind.IDENT) throw err("rpc method name expected but got `" + nameTok.text + "`");
				m.name = nameTok.text;
				m.doc = nameTok.doc;
				m.ns = ns;
				expect("(");
				m.request = qualified();
				expect(")");
				expect(":");
				m.response = qualified();
				metadata(m.meta);
				expect(";");
				s.methods.add(m);
			}
			services.add(s);
		}

		static long parseLong(String s) {
			s = s.trim();
			boolean neg = s.startsWith("-");
			if (neg || s.startsWith("+")) s = s.substring(1);
			long v = s.startsWith("0x") || s.startsWith("0X") ? Long.parseUnsignedLong(s.substring(2), 16) : Long.parseLong(s);
			return neg ? -v : v;
		}
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		final Schema s;
		final String project;
		final StringBuilder sb = new StringBuilder(1 << 16);
		final Map<String, String> csName = new HashMap<>();   // full fbs name → C# path from the project root
		final Map<String, String> nsName = new HashMap<>();   // fbs namespace → C# container path
		final Set<String> usedAttributes = new LinkedHashSet<>();

		Emitter(Schema s, String project) {
			this.s = s;
			this.project = project;
			assignNames();
		}

		// ───────────────────────────── naming ─────────────────────────────

		void assignNames() {
			// namespace segments become nested struct containers; a segment must not clash with a type declared
			// in its parent container, nor with reserved names at the root
			Map<String, Set<String>> taken = new HashMap<>();
			taken.put("", new HashSet<>(RESERVED));
			taken.get("").add(project);
			nsName.put("", "");
			for (Decl d : s.decls.values()) containerFor(d.ns, taken);
			for (Decl d : s.decls.values()) {
				String container = nsName.get(d.ns);
				Set<String> scope = taken.computeIfAbsent(d.ns, k -> new HashSet<>());
				String n = AdHocWriter.unique(d.name, scope);
				if (!container.isEmpty() && n.equals(container.substring(container.lastIndexOf('.') + 1)))
					n = AdHocWriter.unique(n + "Type", scope); // a member cannot bear the name of its enclosing type
				csName.put(d.full(), container.isEmpty() ? n : container + "." + n);
			}
		}

		String containerFor(String ns, Map<String, Set<String>> taken) {
			if (ns.isEmpty()) return "";
			String have = nsName.get(ns);
			if (have != null) return have;
			int dot = ns.lastIndexOf('.');
			String parentNs = dot < 0 ? "" : ns.substring(0, dot);
			String parent = containerFor(parentNs, taken);
			Set<String> scope = taken.computeIfAbsent(parentNs, k -> new HashSet<>());
			String seg = AdHocWriter.unique(ns.substring(dot + 1), scope);
			String path = parent.isEmpty() ? seg : parent + "." + seg;
			nsName.put(ns, path);
			return path;
		}

		/** Resolves a type reference written in namespace {@code from}: current namespace, its parents, then the root. */
		Decl resolve(String ref, String from) {
			String ns = from;
			while (true) {
				Decl d = s.decls.get(ns.isEmpty() ? ref : ns + "." + ref);
				if (d != null) return d;
				if (ns.isEmpty()) break;
				int dot = ns.lastIndexOf('.');
				ns = dot < 0 ? "" : ns.substring(0, dot);
			}
			return null;
		}

		String simple(Decl d) {
			String p = csName.get(d.full());
			return p.substring(p.lastIndexOf('.') + 1);
		}

		// ───────────────────────────── file ─────────────────────────────

		String emit() {
			AdHocWriter.fileHeader(sb, "FlatBuffers2AdHoc", String.join(", ", s.sources),
					s.rootType == null ? "No root_type declared" : "root_type " + s.rootType);
			sb.append("namespace org.flatbuffers {\n");

			Map<String, Integer> dashboard = new LinkedHashMap<>();
			for (Decl d : s.decls.values())
				if (!d.kind.equals("enum")) dashboard.put(project + "." + csName.get(d.full()), null);
			AdHocWriter.dashboard(sb, I1, dashboard);

			sb.append(I1).append("public interface ").append(project).append(" {\n");
			defaults();
			encodingNote();
			fileConstants();
			types();
			hostsAndConnection();
			attributes();
			sb.append(I1).append("}\n");
			sb.append("}\n");
			return sb.toString();
		}

		void fileConstants() {
			if (s.rootType == null && s.fileIdentifier == null && s.fileExtension == null) return;
			sb.append('\n').append(I2).append("/** root_type / file_identifier / file_extension of the schema (non-transmittable constants). */\n");
			sb.append(I2).append("public struct FlatBufferFile {\n");
			if (s.rootType != null) {
				Decl rt = resolve(s.rootType, "");
				sb.append(I2).append(I1).append("public const string root_type = ").append(str(rt == null ? s.rootType : csName.get(rt.full()))).append(";\n");
			}
			if (s.fileIdentifier != null) sb.append(I2).append(I1).append("public const string file_identifier = ").append(str(s.fileIdentifier)).append(";\n");
			if (s.fileExtension != null) sb.append(I2).append(I1).append("public const string file_extension = ").append(str(s.fileExtension)).append(";\n");
			sb.append(I2).append("}\n");
		}

		// ───────────────────────────── namespaces and types ─────────────────────────────

		/**
		 * FlatBuffers vectors and strings carry no declared bound, so AdHoc's 255-item default would truncate real
		 * payloads. Raise the project-wide ceiling instead of stamping an invented {@code [D(N)]} on every field:
		 * the only length this schema language actually states is the fixed array size {@code [T:N]}.
		 */
		void defaults() {
			sb.append('\n').append(I2).append("// FlatBuffers states no length bound for vectors or strings, so AdHoc's 255 default would be a\n");
			sb.append(I2).append("// truncation and any [D(N)] here would be an invention. Raise the project-wide ceiling instead, and\n");
			sb.append(I2).append("// let [D(N)] appear only where the schema really says a size: the fixed arrays `[T:N]` of structs.\n");
			sb.append(I2).append("enum _DefaultMaxLengthOf {\n");
			sb.append(I2).append(I1).append("Arrays  = 65_535,\n");
			sb.append(I2).append(I1).append("Maps    = 65_535,\n");
			sb.append(I2).append(I1).append("Sets    = 65_535,\n");
			sb.append(I2).append(I1).append("Strings = 65_535,\n");
			sb.append(I2).append("}\n");
		}

		/**
		 * Explains the deliberate absence of {@code [A]}/{@code [V]}/{@code [X]}. It is a property of the source
		 * format, not an omission of the converter, so it belongs where the reader will look for it.
		 */
		void encodingNote() {
			sb.append('\n').append(I2).append("// No [A] / [V] / [X] varint attribute appears below, and that is a finding about FlatBuffers rather\n");
			sb.append(I2).append("// than a gap in this converter. FlatBuffers stores every scalar fixed-width and unencoded, so a .fbs\n");
			sb.append(I2).append("// schema never states - and its encoding never implies - where a number's values sit. Declaring a\n");
			sb.append(I2).append("// distribution the source does not know would make the wire larger, not smaller.\n");
			sb.append(I2).append("// Add one by hand wherever you do know the field: [A(min)] for a counter that hugs its floor,\n");
			sb.append(I2).append("// [V(max)] for a remaining budget, [X(amplitude)] for a two-sided delta, [MinMax(a, b)] for a hard\n");
			sb.append(I2).append("// range that should bit-pack. A [Default(\"100\")] below is a hint about the common value, not a\n");
			sb.append(I2).append("// bound, but it is often the clue that tells you which attribute fits.\n");
		}

		/** Emits every declaration, grouped by namespace into nested containers (each container opened once). */
		void types() {
			// namespace tree in first-seen order
			LinkedHashMap<String, List<Decl>> byNs = new LinkedHashMap<>();
			for (Decl d : s.decls.values()) byNs.computeIfAbsent(d.ns, k -> new ArrayList<>()).add(d);
			List<String> all = new ArrayList<>();
			for (String ns : byNs.keySet()) for (String prefix : prefixes(ns)) if (!all.contains(prefix)) all.add(prefix);
			// root-level declarations first
			for (Decl d : byNs.getOrDefault("", new ArrayList<>())) decl(d, I2);
			// then the top-level containers, recursively
			for (String ns : all) if (!ns.contains(".") && !ns.isEmpty()) container(ns, byNs, all, I2);
		}

		static List<String> prefixes(String ns) {
			List<String> out = new ArrayList<>();
			if (ns.isEmpty()) return out;
			int i = -1;
			while ((i = ns.indexOf('.', i + 1)) >= 0) out.add(ns.substring(0, i));
			out.add(ns);
			return out;
		}

		void container(String ns, Map<String, List<Decl>> byNs, List<String> all, String indent) {
			String seg = nsName.get(ns) == null ? ns.substring(ns.lastIndexOf('.') + 1) : nsName.get(ns).substring(nsName.get(ns).lastIndexOf('.') + 1);
			sb.append('\n').append(indent).append("// namespace ").append(ns).append('\n');
			sb.append(indent).append("public struct ").append(seg).append(" {\n");
			for (Decl d : byNs.getOrDefault(ns, new ArrayList<>())) decl(d, indent + I1);
			for (String child : all) if (child.startsWith(ns + ".") && child.indexOf('.', ns.length() + 1) < 0) container(child, byNs, all, indent + I1);
			sb.append(indent).append("}\n");
		}

		void decl(Decl d, String indent) {
			sb.append('\n');
			switch (d.kind) {
				case "table":
				case "struct": table(d, indent); break;
				case "enum": enumDecl(d, indent); break;
				case "union": union(d, indent); break;
				default: break;
			}
		}

		void table(Decl d, String indent) {
			String temporal = TEMPORAL_DESCRIPTORS.get(d.full());
			if (temporal != null) {
				// Deliberately NOT mapped to an AdHoc time type; saying so at the place is the point (README §Time).
				sb.append(indent).append("// Left as an ordinary pack on purpose: this table describes the logical time type of an Arrow column\n");
				sb.append(indent).append("// (its unit, its timezone) and never carries a time value — those live in Arrow's binary buffers,\n");
				sb.append(indent).append("// outside this schema. When you model the values themselves, ").append(temporal).append('\n');
			}
			StringBuilder docs = new StringBuilder(d.doc == null ? "" : d.doc);
			if (d.kind.equals("struct")) docs.append(docs.length() == 0 ? "" : "\n").append("FlatBuffers struct: fixed layout, every field always present.");
			for (Map.Entry<String, String> m : d.meta.entrySet()) docs.append("\n(").append(m.getKey()).append(m.getValue().isEmpty() ? "" : ": " + m.getValue()).append(")");
			doc(sb, indent, docs.toString());
			List<String> attrs = new ArrayList<>();
			if (d.meta.containsKey("force_align")) { attrs.add("ForceAlign(" + d.meta.get("force_align") + ")"); usedAttributes.add("ForceAlign"); }
			if (!attrs.isEmpty()) sb.append(indent).append("[").append(String.join(", ", attrs)).append("]\n");
			String name = simple(d);
			sb.append(indent).append("public class ").append(name).append(" {\n");
			Set<String> fieldNames = new HashSet<>();
			fieldNames.add(name); // a pack cannot contain a field with its own name
			for (Field f : d.fields) field(d, f, indent + I1, fieldNames);
			sb.append(indent).append("}\n");
		}

		void field(Decl owner, Field f, String indent, Set<String> fieldNames) {
			List<String> attrs = new ArrayList<>();
			StringBuilder docs = new StringBuilder(f.doc == null ? "" : f.doc);
			String comment = "";

			// ── type ──
			String type;
			TypeRef t = f.type;
			boolean optionalScalar = "null".equals(f.dflt);
			if (t.isString()) type = "string";
			else if (t.isScalar()) type = SCALARS.get(t.base) + (optionalScalar ? "?" : "");
			else {
				Decl ref = resolve(t.base, owner.ns);
				if (ref == null) {
					type = "int";
					comment = " // unresolved type `" + t.base + "`";
					System.err.println("WARNING " + owner.origin + ": field " + owner.full() + "." + f.name + " has unresolved type `" + t.base + "`, emitted as int");
				} else if (ref.kind.equals("enum") && ref.values.size() < 2) {
					type = SCALARS.get(ref.baseType) + (optionalScalar ? "?" : "");
					comment = " // values: constants container " + csName.get(ref.full());
				} else if (isEmpty(ref)) {
					// AdHoc represents a field typed with an empty pack as its presence flag; do it here explicitly.
					type = "bool";
					comment = " // empty " + ref.kind + " " + csName.get(ref.full()) + ": presence flag";
				} else type = csName.get(ref.full()) + (ref.kind.equals("enum") && optionalScalar ? "?" : "");
			}
			if (t.fixedLen >= 0) { // the one length a .fbs really states
				attrs.add("D(" + t.fixedLen + ")");
				type += "[]";
			} else if (t.vector) {
				if (t.base.equals("ubyte") || t.base.equals("uint8")) type = "Binary";
				type += "[,,]"; // unbounded in FlatBuffers; capped by _DefaultMaxLengthOf at the top of the file
			}
			// A payload FlatBuffers itself leaves opaque stays opaque here. AdHoc could carry it as a nested pack or
			// as a Stream, but the schema does not say which, so the choice belongs to whoever refines this file.
			if (f.meta.containsKey("nested_flatbuffer"))
				docs.append(docs.length() == 0 ? "" : "\n").append("DROPPED: an embedded FlatBuffer of type `").append(f.meta.get("nested_flatbuffer"))
						.append("`, kept as opaque bytes. In AdHoc it would simply be that pack as this field's type.");
			if (f.meta.containsKey("flexbuffer"))
				docs.append(docs.length() == 0 ? "" : "\n").append("DROPPED: a self-describing FlexBuffer, kept as opaque bytes. AdHoc has no untyped value; give it a pack or a Stream.");

			// ── metadata → attributes ──
			if (f.dflt != null && !optionalScalar) { attrs.add("Default(" + str(f.dflt) + ")"); usedAttributes.add("Default"); }
			for (Map.Entry<String, String> m : f.meta.entrySet()) {
				String k = m.getKey(), v = m.getValue();
				switch (k) {
					case "id": attrs.add("FieldId(" + v + ")"); usedAttributes.add("FieldId"); break;
					case "deprecated": attrs.add("Deprecated"); usedAttributes.add("Deprecated"); docs.append(docs.length() == 0 ? "" : "\n").append("⚠ DEPRECATED"); break;
					case "required": attrs.add("Required"); usedAttributes.add("Required"); break;
					case "key": attrs.add("SortKey"); usedAttributes.add("SortKey"); break;
					case "force_align": attrs.add("ForceAlign(" + v + ")"); usedAttributes.add("ForceAlign"); break;
					default: attrs.add("User(" + str(k) + ", " + str(v) + ")"); usedAttributes.add("User"); break;
				}
			}

			doc(sb, indent, docs.toString());
			String name = fieldNames.contains(ident(f.name)) ? AdHocWriter.unique(f.name + "_field", fieldNames) : AdHocWriter.unique(f.name, fieldNames);
			sb.append(indent).append(attrs.isEmpty() ? "" : "[" + String.join(", ", attrs) + "] ").append(type).append(' ').append(name).append(';').append(comment).append('\n');
		}

		void enumDecl(Decl d, String indent) {
			String cs = SCALARS.getOrDefault(d.baseType, "int");
			if (d.values.size() < 2) { // AdHoc rejects enums with fewer than two constants
				sb.append(indent).append("// FlatBuffers enum (base type ").append(d.baseType).append(") with fewer than two values: AdHoc rejects such enums, kept as a constants container.\n");
				doc(sb, indent, d.doc);
				sb.append(indent).append("public struct ").append(simple(d)).append(" {\n");
				Set<String> names = new HashSet<>();
				// int/long/ulong only: the agent's constant reader cannot narrow an integer literal to short/sbyte/...
				String constType = cs.equals("ulong") ? "ulong" : cs.equals("long") || cs.equals("uint") ? "long" : "int";
				for (EnumVal v : d.values) {
					doc(sb, indent + I1, v.doc);
					sb.append(indent).append(I1).append("public const ").append(constType).append(' ').append(AdHocWriter.unique(v.name, names)).append(" = ").append(literal(v.value, cs)).append(";\n");
				}
				if (d.values.isEmpty()) sb.append(indent).append(I1).append("public const bool EMPTY = true;\n");
				sb.append(indent).append("}\n");
				return;
			}
			doc(sb, indent, (d.doc == null ? "" : d.doc + "\n") + "FlatBuffers base type: " + d.baseType + (d.bitFlags ? " (bit_flags)" : ""));
			if (d.bitFlags) sb.append(indent).append("[Flags]\n");
			// The AdHoc generator sizes an enum from its value range itself, and the agent's constant reader only
			// accepts int/long/ulong literals on enum members, so the FlatBuffers base type is documented rather than declared.
			sb.append(indent).append("public enum ").append(simple(d)).append(underlying(d, cs)).append(" {\n");
			Set<String> names = new HashSet<>();
			names.add(simple(d));
			for (EnumVal v : d.values) {
				doc(sb, indent + I1, v.doc);
				sb.append(indent).append(I1).append(AdHocWriter.unique(v.name, names)).append(" = ").append(literal(v.value, cs)).append(",\n");
			}
			sb.append(indent).append("}\n");
		}

		/** No suffix while every value fits an int, {@code : long} / {@code : ulong} beyond that. */
		static String underlying(Decl d, String cs) {
			boolean needLong = false;
			for (EnumVal v : d.values) {
				if (cs.equals("ulong") && v.value < 0) return " : ulong"; // value beyond long.MaxValue
				if (v.value < Integer.MIN_VALUE || Integer.MAX_VALUE < v.value) needLong = true;
			}
			return needLong ? " : long" : "";
		}

		static String literal(long v, String cs) {
			return cs.equals("ulong") ? Long.toUnsignedString(v) : Long.toString(v);
		}

		/** A table/struct without fields or a union without members carries nothing but its presence. */
		static boolean isEmpty(Decl d) {
			switch (d.kind) {
				case "table":
				case "struct": return d.fields.isEmpty();
				case "union": return d.members.isEmpty();
				default: return false;
			}
		}

		/** A union becomes a pack holding one optional field per alternative; exactly one is expected to be set. */
		void union(Decl d, String indent) {
			StringBuilder docs = new StringBuilder(d.doc == null ? "" : d.doc);
			docs.append(docs.length() == 0 ? "" : "\n").append("FlatBuffers union: exactly one of the fields below is set.");
			doc(sb, indent, docs.toString());
			sb.append(indent).append("public class ").append(simple(d)).append(" {\n");
			Set<String> names = new HashSet<>();
			names.add(simple(d));
			for (UnionVal u : d.members) {
				String type;
				String comment = "";
				if (u.type.equals("string")) type = "string";
				else {
					Decl ref = resolve(u.type, d.ns);
					if (ref == null) {
						type = "int";
						comment = " // unresolved type `" + u.type + "`";
						System.err.println("WARNING " + d.origin + ": union " + d.full() + " member `" + u.type + "` unresolved");
					} else if (isEmpty(ref)) {
						type = "bool";
						comment = " // empty " + ref.kind + " " + csName.get(ref.full()) + ": presence flag";
					} else type = csName.get(ref.full());
				}
				doc(sb, indent + I1, u.doc);
				sb.append(indent).append(I1).append(type).append(' ').append(AdHocWriter.unique(u.alias, names)).append(';').append(comment).append('\n');
			}
			sb.append(indent).append("}\n");
		}

		// ───────────────────────────── topology ─────────────────────────────

		void hostsAndConnection() {
			sb.append('\n').append(I2).append("// ═════════════════════════ demo topology ═════════════════════════\n\n");
			AdHocWriter.host(sb, I2, "Client", "The side that owns the root object and calls the rpc_service methods.");
			AdHocWriter.host(sb, I2, "Server", null);

			// RPC request/response packs must not also appear in the always-active exchange state on the same side.
			Set<String> rpcPacks = new HashSet<>();
			List<String> rpcLines = new ArrayList<>();
			Set<String> methodNames = new HashSet<>(RESERVED);
			for (Service svc : s.services)
				for (RpcMethod m : svc.methods) {
					Decl req = resolve(m.request, m.ns), rsp = resolve(m.response, m.ns);
					if (req == null || rsp == null) {
						System.err.println("WARNING rpc " + svc.name + "." + m.name + ": unresolved request/response type, skipped");
						continue;
					}
					rpcPacks.add(req.full());
					rpcPacks.add(rsp.full());
					StringBuilder line = new StringBuilder();
					StringBuilder docs = new StringBuilder(m.doc == null ? "" : m.doc);
					docs.append(docs.length() == 0 ? "" : "\n").append("rpc_service ").append(svc.name);
					for (Map.Entry<String, String> e : m.meta.entrySet()) docs.append(", ").append(e.getKey()).append(e.getValue().isEmpty() ? "" : ": " + e.getValue());
					StringBuilder tmp = new StringBuilder();
					doc(tmp, I2 + I1, docs.toString());
					line.append(tmp);
					line.append(I2).append(I1).append("(L____________, ").append(csName.get(rsp.full())).append(") ")
							.append(AdHocWriter.unique(m.name, methodNames)).append("(").append(csName.get(req.full())).append(" req);\n");
					rpcLines.add(line.toString());
				}

			List<String> exchange = new ArrayList<>();
			for (Decl d : s.decls.values())
				if (d.kind.equals("table") && !rpcPacks.contains(d.full())) exchange.add(csName.get(d.full()));

			AdHocWriter.connectionOpen(sb, I2, "Connection", "Client", "Server");
			if (!rpcLines.isEmpty()) {
				sb.append(I2).append(I1).append("// rpc_service methods: Client calls, Server answers (streaming modes are noted in the docs).\n");
				for (String l : rpcLines) sb.append(l);
				sb.append('\n');
			}
			if (!exchange.isEmpty()) {
				sb.append(I2).append(I1).append("// Every table not owned by an rpc method, in both directions; the FSM never transitions.\n");
				AdHocWriter.statePacks(sb, I2 + I1, "_____lr_____", "Exchange", exchange);
			}
			sb.append(I2).append("}\n");
		}

		// ───────────────────────────── attributes ─────────────────────────────

		void attributes() {
			if (usedAttributes.isEmpty()) return;
			sb.append('\n').append(I2).append("// ═════════════════════════ FlatBuffers metadata attributes ═════════════════════════\n\n");
			sb.append(I2).append("// AdHoc custom attributes: carried into the generated code as constants attached to the field/pack.\n");
			sb.append(I2).append("// Declared inside the project interface; the exchange state lists its packs explicitly, so they are not collected.\n\n");
			if (usedAttributes.contains("Default")) AdHocWriter.attribute(sb, I2, "Default", "Default value of a scalar field, as written in the schema (number, enum member, true/false, nan, inf).", "string value");
			if (usedAttributes.contains("FieldId")) AdHocWriter.attribute(sb, I2, "FieldId", "Explicit vtable slot (id: N) of the field in the FlatBuffers table.", "long id");
			if (usedAttributes.contains("Deprecated")) AdHocWriter.attribute(sb, I2, "Deprecated", "The field is deprecated in the FlatBuffers schema and must not be written.");
			if (usedAttributes.contains("Required")) AdHocWriter.attribute(sb, I2, "Required", "The (non-scalar) field is required in the FlatBuffers schema.");
			if (usedAttributes.contains("SortKey")) AdHocWriter.attribute(sb, I2, "SortKey", "The field is the (key) used to sort vectors of this table/struct.");
			if (usedAttributes.contains("ForceAlign")) AdHocWriter.attribute(sb, I2, "ForceAlign", "force_align: N alignment requested in the FlatBuffers schema.", "long alignment");
			if (usedAttributes.contains("User")) {
				sb.append(I2).append("/** Any other FlatBuffers field attribute (hash, nested_flatbuffer, flexbuffer, cpp_type, user-declared ...) as name/value. */\n");
				sb.append(I2).append("[AttributeUsage(AttributeTargets.All, AllowMultiple = true)]\n");
				sb.append(I2).append("public class UserAttribute : Attribute { public UserAttribute(string name, string value) { } }\n\n");
			}
		}
	}
}
