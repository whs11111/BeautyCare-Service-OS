import { nextTick } from "vue";

export const insertEmoji = async (input, inputRef, emoji) => {
  const field = inputRef.value?.textarea || inputRef.value?.input;
  const start = field?.selectionStart ?? input.value.length;
  const end = field?.selectionEnd ?? start;
  input.value = input.value.slice(0, start) + emoji + input.value.slice(end);
  await nextTick();
  field?.focus();
  field?.setSelectionRange(start + emoji.length, start + emoji.length);
};
