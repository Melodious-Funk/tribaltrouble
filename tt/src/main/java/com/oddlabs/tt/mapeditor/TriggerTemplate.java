package com.oddlabs.tt.mapeditor;

import org.jspecify.annotations.NonNull;

/**
 * Ready-made triggers for what campaign levels often do, to start from instead of an empty one. Areas, units and
 * buildings are left for the maker to pick, and the level's problem check points out any that are not.
 */
enum TriggerTemplate {
    OPENING("template_opening") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            Step dialog = action(trigger, ActionKind.DIALOG);
            dialog.setText(Param.HEADER, CampaignEditor.i18n("template_opening_header"));
            dialog.setText(Param.TEXT, CampaignEditor.i18n("template_opening_text"));
            dialog.set(Param.FACE, 1);
            action(trigger, ActionKind.OBJECTIVE).setText(Param.TEXT,
                    CampaignEditor.i18n("template_opening_objective"));
        }
    },
    VICTORY("template_victory") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.ENEMIES_DEFEATED);
            action(trigger, ActionKind.VICTORY);
        }
    },
    PROTECT("template_protect") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.OBJECT_DESTROYED);
            action(trigger, ActionKind.DEFEAT).setText(Param.TEXT, CampaignEditor.i18n("template_protect_text"));
        }
    },
    WAVES("template_waves") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.TIME_ELAPSED);
            trigger.condition.set(Param.SECONDS, 180);
            trigger.repeat = true;
            Step spawn = action(trigger, ActionKind.SPAWN_UNITS);
            spawn.set(Param.COUNT, 6);
            Step attack = action(trigger, ActionKind.ATTACK_PLAYER);
            attack.set(Param.TARGET_PLAYER, 0);
            attack.set(Param.COUNT, 6);
        }
    },
    REINFORCEMENTS("template_reinforcements") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.UNITS_IN_AREA);
            trigger.condition.set(Param.PLAYER, 0);
            trigger.condition.set(Param.COUNT, 1);
            Step spawn = action(trigger, ActionKind.SPAWN_UNITS);
            spawn.set(Param.PLAYER, 0);
            action(trigger, ActionKind.MESSAGE).setText(Param.TEXT,
                    CampaignEditor.i18n("template_reinforcements_text"));
        }
    },
    ALLIES("template_allies") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.UNITS_NEAR_OBJECT);
            trigger.condition.set(Param.PLAYER, 0);
            Step dialog = action(trigger, ActionKind.DIALOG);
            dialog.setText(Param.HEADER, CampaignEditor.i18n("template_allies_header"));
            dialog.setText(Param.TEXT, CampaignEditor.i18n("template_allies_text"));
            Step team = action(trigger, ActionKind.SET_TEAM);
            team.set(Param.PLAYER, 2);
            team.set(Param.TEAM, 0);
        }
    },
    BETRAYAL("template_betrayal") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.TIME_ELAPSED);
            trigger.condition.set(Param.SECONDS, 300);
            action(trigger, ActionKind.MESSAGE).setText(Param.TEXT, CampaignEditor.i18n("template_betrayal_text"));
            Step team = action(trigger, ActionKind.SET_TEAM);
            team.set(Param.PLAYER, 2);
            team.set(Param.TEAM, 2);
            Step attack = action(trigger, ActionKind.ATTACK_PLAYER);
            attack.set(Param.PLAYER, 2);
            attack.set(Param.TARGET_PLAYER, 0);
        }
    },
    CAPTIVES("template_captives") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.UNITS_IN_AREA);
            trigger.condition.set(Param.PLAYER, 0);
            trigger.condition.set(Param.COUNT, 1);
            Step free = action(trigger, ActionKind.CHANGE_OWNER_AREA);
            free.set(Param.PLAYER, 2);
            free.set(Param.NEW_OWNER, 0);
            action(trigger, ActionKind.MESSAGE).setText(Param.TEXT, CampaignEditor.i18n("template_captives_text"));
        }
    },
    STATUES_GUARD("template_statues_guard") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.STATUES_LEFT);
            trigger.condition.set(Param.COUNT, 1);
            action(trigger, ActionKind.DEFEAT).setText(Param.TEXT, CampaignEditor.i18n("template_statues_text"));
        }
    },
    STATUES_RAID("template_statues_raid") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.UNITS_IN_AREA);
            trigger.condition.set(Param.COUNT, 3);
            trigger.repeat = true;
            action(trigger, ActionKind.REMOVE_STATUES);
            action(trigger, ActionKind.MESSAGE).setText(Param.TEXT, CampaignEditor.i18n("template_raid_text"));
        }
    },
    GARRISON("template_garrison") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            Step board = action(trigger, ActionKind.BOARD_FROM_AREA);
            board.set(Param.COUNT, 1);
        }
    },
    LANDING("template_landing") {
        @Override
        void fill(Scenario.@NonNull Trigger trigger) {
            trigger.condition = Step.condition(ConditionKind.TIME_ELAPSED);
            trigger.condition.set(Param.SECONDS, 120);
            Step deploy = action(trigger, ActionKind.DEPLOY_FROM);
            deploy.set(Param.COUNT, 8);
            Step attack = action(trigger, ActionKind.ATTACK_PLAYER);
            attack.set(Param.TARGET_PLAYER, 0);
            attack.set(Param.COUNT, 8);
        }
    };

    private final @NonNull String key;

    TriggerTemplate(@NonNull String key) {
        this.key = key;
    }

    @NonNull String getName() {
        return CampaignEditor.i18n(key);
    }

    @NonNull String getHelp() {
        return CampaignEditor.i18n(key + "_help");
    }

    /** A new trigger made from the template, named after it. */
    Scenario.@NonNull Trigger create(@NonNull Scenario scenario) {
        Scenario.Trigger trigger = new Scenario.Trigger(scenario.newId(), getName(),
                Step.condition(ConditionKind.GAME_STARTED));
        fill(trigger);
        return trigger;
    }

    /** Sets the condition, actions and settings; the condition starts as the level starting. */
    abstract void fill(Scenario.@NonNull Trigger trigger);

    private static @NonNull Step action(Scenario.@NonNull Trigger trigger, @NonNull ActionKind kind) {
        Step step = Step.action(kind);
        trigger.actions.add(step);
        return step;
    }
}
