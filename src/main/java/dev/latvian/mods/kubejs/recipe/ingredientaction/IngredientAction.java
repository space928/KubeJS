package dev.latvian.mods.kubejs.recipe.ingredientaction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.architectury.hooks.item.ItemStackHooks;
import dev.latvian.mods.kubejs.item.ItemStackJS;
import dev.latvian.mods.kubejs.item.ingredient.IngredientJS;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class IngredientAction extends IngredientActionFilter {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	public static final Map<String, Function<JsonObject, IngredientAction>> FACTORY_MAP = new HashMap<>();

	// TODO: properly codec-ify this
	public static final Codec<IngredientAction> CODEC = Codec.PASSTHROUGH.comapFlatMap(
		dynamic -> fromJson(dynamic.convert(JsonOps.INSTANCE).getValue()),
		action -> new Dynamic<>(JsonOps.INSTANCE, action.toJson())
	);

	static {
		FACTORY_MAP.put("custom", json -> new CustomIngredientAction(json.get("id").getAsString()));
		FACTORY_MAP.put("damage", json -> new DamageAction(json.get("damage").getAsInt()));
		FACTORY_MAP.put("replace", json -> new ReplaceAction(ItemStackJS.resultFromRecipeJson(json.get("item"))));
		FACTORY_MAP.put("keep", json -> new KeepAction());
	}

	public static List<IngredientAction> parseList(JsonElement json) {
		if (json == null || !json.isJsonArray()) {
			return List.of();
		}

		List<IngredientAction> list = new ArrayList<>();

		for (var e : json.getAsJsonArray()) {
			fromJson(e).result().ifPresent(list::add);
		}

		return list.isEmpty() ? List.of() : list;
	}

	private static DataResult<IngredientAction> fromJson(JsonElement json) {
		if (json.isJsonArray()) {
			return DataResult.error(() -> "Unexpected array, did you mean to use parseList?");
		} else if (!json.isJsonObject()) {
			return DataResult.error(() -> "Expected object, got " + json);
		}

		var o = json.getAsJsonObject();
		var type = o.has("type") ? o.get("type").getAsString() : "";

		var factory = FACTORY_MAP.get(type);
		var action = factory == null ? null : factory.apply(o);

		if (action != null) {
			action.filterIndex = GsonHelper.getAsInt(o, "filter_index", -1);
			action.filterIngredient = o.has("filter_ingredient") ? IngredientJS.of(o.get("filter_ingredient")) : null;
			return DataResult.success(action);
		}

		return DataResult.error(() -> "Unknown ingredient action type: " + type);
	}

	public static List<IngredientAction> readList(FriendlyByteBuf buf) {
		var s = buf.readVarInt();

		if (s <= 0) {
			return List.of();
		}

		List<IngredientAction> list = new ArrayList<>();

		for (var i = 0; i < s; i++) {
			var factory = FACTORY_MAP.get(buf.readUtf());
			var action = factory == null ? null : factory.apply(GSON.fromJson(buf.readUtf(), JsonObject.class));

			if (action != null) {
				action.filterIndex = buf.readVarInt();
				var ij = buf.readUtf();
				action.filterIngredient = ij.isEmpty() ? null : IngredientJS.of(GSON.fromJson(ij, JsonObject.class));
				list.add(action);
			}
		}

		return list.isEmpty() ? List.of() : list;
	}

	public static void writeList(FriendlyByteBuf buf, @Nullable List<IngredientAction> list) {
		if (list == null || list.isEmpty()) {
			buf.writeVarInt(0);
			return;
		}

		buf.writeVarInt(list.size());

		for (var action : list) {
			buf.writeUtf(action.getType());
			var json = new JsonObject();
			action.toJson(json);
			buf.writeUtf(GSON.toJson(json));
			buf.writeVarInt(action.filterIndex);
			buf.writeUtf(action.filterIngredient == null ? "" : GSON.toJson(action.filterIngredient.toJsonJS()));
		}
	}

	public static ItemStack getRemaining(CraftingContainer container, int index, List<IngredientAction> ingredientActions) {
		var stack = container.getItem(index);

		if (stack == null || stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		for (var action : ingredientActions) {
			if (action.checkFilter(index, stack)) {
				return action.transform(stack.copy(), index, container);
			}
		}

		if (ItemStackHooks.hasCraftingRemainingItem(stack)) {
			return ItemStackHooks.getCraftingRemainingItem(stack);
		}

		return ItemStack.EMPTY;
	}

	public abstract ItemStack transform(ItemStack old, int index, CraftingContainer container);

	public abstract String getType();

	public void toJson(JsonObject json) {
	}

	public final JsonObject toJson() {
		var json = new JsonObject();
		json.addProperty("type", getType());

		if (filterIngredient != null) {
			json.add("filter_ingredient", filterIngredient.toJsonJS());
		}

		if (filterIndex != -1) {
			json.addProperty("filter_index", filterIndex);
		}

		toJson(json);
		return json;
	}
}