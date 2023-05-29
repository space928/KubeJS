package dev.latvian.mods.kubejs.recipe.schema.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.architectury.platform.Platform;
import dev.latvian.mods.kubejs.item.InputItem;
import dev.latvian.mods.kubejs.item.OutputItem;
import dev.latvian.mods.kubejs.recipe.RecipeJS;
import dev.latvian.mods.kubejs.recipe.RecipeKey;
import dev.latvian.mods.kubejs.recipe.component.ItemComponents;
import dev.latvian.mods.kubejs.recipe.component.NumberComponent;
import dev.latvian.mods.kubejs.recipe.component.RecipeComponent;
import dev.latvian.mods.kubejs.recipe.component.RecipeComponentWithParent;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchema;

public interface CookingRecipeSchema {
	class CookingRecipeJS extends RecipeJS {
		public RecipeJS xp(float xp) {
			return setValue(CookingRecipeSchema.XP, Math.max(0F, xp));
		}

		public RecipeJS cookingTime(int time) {
			return setValue(CookingRecipeSchema.COOKING_TIME, Math.max(0, time));
		}
	}

	RecipeComponent<OutputItem> PLATFORM_OUTPUT_ITEM = new RecipeComponentWithParent<>() {
		@Override
		public RecipeComponent<OutputItem> parentComponent() {
			return ItemComponents.OUTPUT;
		}

		@Override
		public JsonElement write(RecipeJS recipe, OutputItem value) {
			if (Platform.isForge()) {
				return ItemComponents.OUTPUT.write(recipe, value);
			} else {
				return new JsonPrimitive(value.item.kjs$getId());
			}
		}

		@Override
		public String toString() {
			return parentComponent().toString();
		}
	};

	RecipeKey<OutputItem> RESULT = PLATFORM_OUTPUT_ITEM.key(0, "result");
	RecipeKey<InputItem> INGREDIENT = ItemComponents.INPUT.key(1, "ingredient");
	RecipeKey<Float> XP = NumberComponent.FLOAT.optional(0F).key(2, "experience").alt("xp");
	RecipeKey<Integer> COOKING_TIME = NumberComponent.INT.optional(200).key(3, "cookingtime").alt("cookingTime");

	RecipeSchema SCHEMA = new RecipeSchema(CookingRecipeJS.class, CookingRecipeJS::new, RESULT, INGREDIENT, XP, COOKING_TIME);
}
