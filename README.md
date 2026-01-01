src
└── main
    ├── java
    │   └── com.hjh_database
    │       ├── Hjh_database (主类/Main Class)
    │       ├── api
    │       ├── command
    │       │   ├── AdminCommand
    │       │   ├── ResourceReloadCommand
    │       │   ├── StatsCommand
    │       │   ├── TestMobCommand
    │       │   ├── WeaponCommand
    │       │   └── ZfCommand
    │       ├── data
    │       │   ├── DatabaseManager
    │       │   ├── PlayerData
    │       │   └── PlayerManager
    │       ├── dz  (可能是锻造/打造系统模块?)
    │       │   ├── command
    │       │   │   └── DzCommand
    │       │   ├── data
    │       │   │   ├── DzPlayerData
    │       │   │   └── DzRecipe
    │       │   ├── gui
    │       │   │   ├── AdminCategoryGui
    │       │   │   ├── AdminRecipeListGui
    │       │   │   ├── CategoryGui
    │       │   │   ├── PlayerRecipeListGui
    │       │   │   ├── RecipeCraftingGui
    │       │   │   ├── RecipeEditorGui
    │       │   │   └── RecipePreviewGui
    │       │   ├── listener
    │       │   │   └── StationListener
    │       │   └── manager
    │       │   │   └── RecipeManager
    │       ├── listener
    │       │   ├── CombatListener
    │       │   ├── MenuListener
    │       │   ├── PlayerListener
    │       │   └── SpellListener
    │       ├── resource
    │       │   ├── ResourceItem
    │       │   ├── ResourceListener
    │       │   └── ResourceManager
    │       ├── skill.element_zf
    │       │   ├── impl
    │       │   │   └── MetalSkill
    │       │   ├── ElementSkill (Interface)
    │       │   └── ElementZfManager
    │       ├── ui
    │       │   └── MenuManager
    │       ├── util
    │       │   ├── DzUtil
    │       │   └── ItemUtil
    │       └── weapon
    │           ├── ArmorManager
    │           └── WeaponManager
    └── resources
        ├── recipes
        │   └── weapon.yml
        ├── armors.yml
        ├── config.yml
        ├── element_zf.yml
        ├── forge_settings.yml
        ├── menus.yml
        ├── plugin.yml
        └── weapons.yml
