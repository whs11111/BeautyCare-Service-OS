export const getFileIcon = (name = "") => {
  const extension = name.split(".").pop().toLowerCase();
  if (["doc", "docx"].includes(extension)) return { label: "W", tone: "word" };
  if (["xls", "xlsx", "csv"].includes(extension)) return { label: "X", tone: "excel" };
  if (["ppt", "pptx"].includes(extension)) return { label: "P", tone: "slides" };
  if (extension === "pdf") return { label: "PDF", tone: "pdf" };
  if (["zip", "rar", "7z"].includes(extension)) return { label: "ZIP", tone: "archive" };
  if (["txt", "md"].includes(extension)) return { label: "TXT", tone: "text" };
  return { label: "?", tone: "other" };
};
