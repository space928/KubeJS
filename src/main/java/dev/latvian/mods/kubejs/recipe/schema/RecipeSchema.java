package dev.latvian.mods.kubejs.recipe.schema;

import com.google.gson.JsonObject;
import dev.latvian.mods.kubejs.DevProperties;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.recipe.KubeRecipe;
import dev.latvian.mods.kubejs.recipe.RecipeKey;
import dev.latvian.mods.kubejs.recipe.RecipeTypeFunction;
import dev.latvian.mods.kubejs.recipe.component.UniqueIdBuilder;
import dev.latvian.mods.kubejs.util.Cast;
import dev.latvian.mods.kubejs.util.JsonUtils;
import dev.latvian.mods.rhino.util.RemapForJS;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.function.Function;

/**
 * A recipe schema is a set of keys that defines how a recipe is constructed
 * from both KubeJS scripts (through the {@link #constructors}) and JSON files
 * (using the {@link #deserialize(RecipeTypeFunction, ResourceLocation, JsonObject)} method).
 * <p>
 * The schema also defines a {@link #recipeFactory} in order to create a {@link KubeRecipe} object that
 * implements serialization logic, post-load validation ({@link KubeRecipe#afterLoaded()}),
 * as well as entirely custom logic such as additional methods a developer may call from scripts.
 *
 * @see RecipeKey
 * @see KubeRecipe
 */
public class RecipeSchema {
	public static final Function<KubeRecipe, String> DEFAULT_UNIQUE_ID_FUNCTION = r -> null;

	public KubeRecipeFactory recipeFactory;
	public final RecipeKey<?>[] keys;
	public final Map<String, RecipeSchemaFunction> functions;
	private int inputCount;
	private int outputCount;
	private int minRequiredArguments;
	private Int2ObjectMap<RecipeConstructor> constructors;
	public Function<KubeRecipe, String> uniqueIdFunction;
	boolean hidden;

	/**
	 * Defines a new recipe schema that creates recipes of the given {@link KubeRecipe} subclass.
	 * <p>
	 * Keys are defined in order of their appearance in the autogenerated constructor, where optional keys
	 * must be placed after all required keys.
	 *
	 * @param keys The keys that define this schema.
	 */
	public RecipeSchema(RecipeKey<?>... keys) {
		this.recipeFactory = KubeRecipeFactory.DEFAULT;
		this.keys = keys;
		this.functions = new LinkedHashMap<>(0);
		this.minRequiredArguments = 0;
		this.inputCount = 0;
		this.outputCount = 0;

		var set = new HashSet<String>();

		for (int i = 0; i < keys.length; i++) {
			if (keys[i].optional()) {
				if (minRequiredArguments == 0) {
					minRequiredArguments = i;
				}
			} else if (minRequiredArguments > 0) {
				throw new IllegalArgumentException("Required key '" + keys[i].name + "' must be ahead of optional keys!");
			}

			if (!set.add(keys[i].name)) {
				throw new IllegalArgumentException("Duplicate key '" + keys[i].name + "' found!");
			}

			if (keys[i].role.isInput()) {
				inputCount++;
			} else if (keys[i].role.isOutput()) {
				outputCount++;
			}

			if (keys[i].alwaysWrite && keys[i].optional() && keys[i].optional.isDefault()) {
				throw new IllegalArgumentException("Key '" + keys[i] + "' can't have alwaysWrite() enabled with defaultOptional()!");
			}
		}

		if (minRequiredArguments == 0) {
			minRequiredArguments = keys.length;
		}

		this.uniqueIdFunction = DEFAULT_UNIQUE_ID_FUNCTION;
		this.hidden = false;
	}

	public RecipeSchema factory(KubeRecipeFactory factory) {
		this.recipeFactory = factory;
		return this;
	}

	public RecipeSchema constructor(RecipeConstructor constructor) {
		if (constructors == null) {
			constructors = new Int2ObjectArrayMap<>(keys.length - minRequiredArguments + 1);
		}

		if (constructors.put(constructor.keys.length, constructor) != null) {
			throw new IllegalStateException("Constructor with " + constructor.keys.length + " arguments already exists!");
		}

		return this;
	}

	/**
	 * Defines an additional constructor to be for this schema.
	 *
	 * @param keys The arguments that this constructor takes in.
	 * @return This schema.
	 * @implNote If a constructor is manually defined using this method, constructors will not be automatically generated.
	 */
	@RemapForJS("addConstructor") // constructor is a reserved word in TypeScript, so remap this for scripters who use .d.ts files for typing hints
	public RecipeSchema constructor(RecipeKey<?>... keys) {
		return constructor(new RecipeConstructor(keys));
	}

	public RecipeSchema uniqueId(Function<KubeRecipe, String> uniqueIdFunction) {
		this.uniqueIdFunction = uniqueIdFunction;
		return this;
	}

	public RecipeSchema uniqueId(RecipeKey<?> key) {
		return uniqueId(r -> {
			var value = r.getValue(key);

			if (value != null) {
				var builder = new UniqueIdBuilder(new StringBuilder());
				key.component.buildUniqueId(builder, Cast.to(value));
				return builder.build();
			}

			return null;
		});
	}

	public RecipeSchema uniqueIds(SequencedCollection<RecipeKey<?>> keys) {
		if (keys.isEmpty()) {
			return uniqueId(DEFAULT_UNIQUE_ID_FUNCTION);
		} else if (keys.size() == 1) {
			return uniqueId(keys.getFirst());
		}

		return uniqueId(r -> {
			var sb = new StringBuilder();
			var builder = new UniqueIdBuilder(new StringBuilder());
			boolean first = true;

			for (var key : keys) {
				var value = r.getValue(key);

				if (value != null) {
					key.component.buildUniqueId(builder, Cast.to(value));
					var result = builder.build();

					if (result != null) {
						if (first) {
							first = false;
						} else {
							sb.append('/');
						}

						sb.append(result);
					}
				}
			}

			return sb.isEmpty() ? null : sb.toString();
		});
	}

	public Int2ObjectMap<RecipeConstructor> constructors() {
		if (constructors == null) {
			var keys1 = Arrays.stream(keys).filter(RecipeKey::includeInAutoConstructors).toArray(RecipeKey[]::new);

			constructors = keys1.length == 0 ? new Int2ObjectArrayMap<>() : new Int2ObjectArrayMap<>(keys1.length - minRequiredArguments + 1);
			boolean dev = DevProperties.get().logRecipeDebug;

			if (dev) {
				KubeJS.LOGGER.info("Generating constructors for " + new RecipeConstructor(keys1));
			}

			for (int a = minRequiredArguments; a <= keys1.length; a++) {
				var k = new RecipeKey<?>[a];
				System.arraycopy(keys1, 0, k, 0, a);
				var c = new RecipeConstructor(k);
				constructors.put(a, c);

				if (dev) {
					KubeJS.LOGGER.info("> " + a + ": " + c);
				}
			}
		}

		return constructors;
	}

	public int minRequiredArguments() {
		return minRequiredArguments;
	}

	public int inputCount() {
		return inputCount;
	}

	public int outputCount() {
		return outputCount;
	}

	public boolean isHidden() {
		return hidden;
	}

	public KubeRecipe deserialize(RecipeTypeFunction type, @Nullable ResourceLocation id, JsonObject json) {
		var r = recipeFactory.create();
		r.type = type;
		r.id = id;
		r.json = json;
		r.newRecipe = id == null;
		r.initValues(id == null);

		if (id != null && DevProperties.get().logRecipeDebug) {
			r.originalJson = (JsonObject) JsonUtils.copy(json);
		}

		r.deserialize(false);
		return r;
	}

	public RecipeSchema function(String name, RecipeSchemaFunction function) {
		this.functions.put(name, function);
		return this;
	}

	public <T> RecipeSchema setOpFunction(String name, RecipeKey<T> key, T value) {
		return function(name, new RecipeSchemaFunction.SetFunction<>(key, value));
	}

	public <T> RecipeKey<T> getKey(String id) {
		for (var key : keys) {
			if (key.name.equals(id)) {
				return (RecipeKey<T>) key;
			}
		}

		throw new NullPointerException("Key '" + id + "' not found");
	}
}
