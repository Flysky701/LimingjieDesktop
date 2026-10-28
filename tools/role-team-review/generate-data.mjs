import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const assets = path.join(root, "android", "app", "src", "main", "assets", "resource-packs", "cn-bilibili");
const catalog = JSON.parse(fs.readFileSync(path.join(assets, "characters.landosolroster.json"), "utf8"));
const icons = JSON.parse(fs.readFileSync(path.join(assets, "icons.json"), "utf8"));
const tierSummary = JSON.parse(
  fs.readFileSync(path.join(root, "攻略", "tierlist_91x91_output", "summary.json"), "utf8"),
);

const tierScore = { SS: 6, S: 5, A: 4, B: 3, C: 2, D: 1 };
const normalize = (value) => String(value ?? "")
  .trim()
  .replaceAll("（", "(")
  .replaceAll("）", ")")
  .replace(/[\s\n\t]/g, "");

const scoreByName = new Map();
for (const tiers of Object.values(tierSummary)) {
  for (const [tier, names] of Object.entries(tiers)) {
    const score = tierScore[tier] ?? 0;
    for (const name of names) {
      const key = normalize(name);
      scoreByName.set(key, Math.max(scoreByName.get(key) ?? 0, score));
    }
  }
}

const iconsByOwner = new Map();
for (const icon of icons.characterIcons) {
  const current = iconsByOwner.get(icon.ownerId) ?? [];
  current.push(icon);
  iconsByOwner.set(icon.ownerId, current);
}
const variantPriority = (variant) => {
  if (variant === "61") return 400;
  if (variant === "31") return 300;
  if (variant === "11") return 200;
  return Number.parseInt(variant, 10) || 0;
};

let matchedScores = 0;
const characters = catalog.characters.map((character) => {
  const names = [character.name, character.officialName, ...(character.aliases ?? [])]
    .map(normalize)
    .filter(Boolean);
  const matched = Math.max(0, ...names.map((name) => scoreByName.get(name) ?? 0));
  if (matched > 0) matchedScores += 1;
  const icon = (iconsByOwner.get(character.id) ?? [])
    .slice()
    .sort((left, right) => variantPriority(right.variant) - variantPriority(left.variant))[0];
  return {
    id: character.id,
    name: character.name,
    officialName: character.officialName || "",
    aliases: character.aliases ?? [],
    available: character.available !== false,
    icon: icon
      ? `../../android/app/src/main/assets/resource-packs/cn-bilibili/${icon.file.replaceAll("\\", "/")}`
      : null,
    referenceScore: matched || 1,
    scoreSource: matched ? "tierlist-highest" : "default-D",
    attribute: null,
    roleClass: null,
    damageType: null,
    position: null,
    physicalDamage: null,
    magicDamage: null,
    physicalSupport: null,
    magicSupport: null,
    universalSupport: null,
    survivalSupport: null,
    controlSupport: null,
    reliableVanguard: null,
    selfSustain: null,
    healing: null,
    regeneration: null,
    physicalAttackReduction: null,
    magicAttackReduction: null,
    physicalDefenseDown: null,
    magicDefenseDown: null,
    aoeDamage: null,
    singleTargetDamage: null,
    bossMechanismValue: null,
    pureTank: null,
    pureHealer: null,
    reviewNote: "",
  };
});

const openingGuilds = [
  { id: 1, name: "美食殿堂", roleIds: ["1075", "1351", "1059"] },
  { id: 2, name: "破晓之星", roleIds: ["1089", "1088", "1003", "1801", "1225"] },
  { id: 3, name: "咲恋救济院", roleIds: ["1145", "1213", "1085", "1077", "1121", "1308", "1023", "1086", "1103"] },
  { id: 4, name: "王宫骑士团", roleIds: ["1242", "1339", "1136", "1115", "1236", "1238"] },
  { id: 5, name: "拉比林斯", roleIds: ["1091", "1171", "1011"] },
];

const payload = {
  generatedAt: new Date().toISOString(),
  sources: {
    catalog: "characters.landosolroster.json",
    icons: "icons.json",
    score: "攻略/tierlist_91x91_output/summary.json（同名角色取最高档，未匹配按现有脚本规则暂记D/1）",
    calibrationWorkbook: "攻略/公主连结角色黎明界强度表有图片.xlsx（本页尚未读取其属性与职阶列）",
  },
  coverage: {
    characters: characters.length,
    availableCharacters: characters.filter((character) => character.available).length,
    icons: characters.filter((character) => character.icon).length,
    matchedScores,
    defaultScores: characters.length - matchedScores,
  },
  openingGuilds,
  characters,
};

fs.writeFileSync(
  path.join(here, "data.js"),
  `window.LABYRINTH_REVIEW_DATA = ${JSON.stringify(payload)};\n`,
  "utf8",
);

console.log(JSON.stringify(payload.coverage));
