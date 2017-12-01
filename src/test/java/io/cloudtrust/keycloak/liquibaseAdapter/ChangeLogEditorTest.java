package io.cloudtrust.keycloak.liquibaseAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import io.cloudtrust.keycloak.liquibaseAdapter.pojo.liquibase.DatabaseChangeLog;

import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

@RunWith(JUnit4.class)
public class ChangeLogEditorTest {
    private ChangeLogEditor logEditor = new ChangeLogEditor();

    @Test
    public void testLoadDatabaseChangeLog() throws JAXBException {
        logEditor.loadDatabaseChangeLog("/home/fratt/CloudTrust/git/keycloak-3.3.0.Final/keycloak/model/jpa/src/main/resources/META-INF/jpa-changelog-1.0.0.Final.xml");
        assertNotNull(logEditor.getDcl());
        List<DatabaseChangeLog.ChangeSet> changeSetList = logEditor.getDcl().getChangeSetOrIncludeOrIncludeAll()
                .stream().filter(DatabaseChangeLog.ChangeSet.class::isInstance).map(DatabaseChangeLog.ChangeSet.class::cast)
                .collect(Collectors.toList());
        assertEquals(1, changeSetList.size());
    }
    @Test
    public void testMergeAddPrimeryKeyIntoCreateTable() throws JAXBException {
        logEditor.loadDatabaseChangeLog("/home/fratt/CloudTrust/git/keycloak-3.3.0.Final/keycloak/model/jpa/src/main/resources/META-INF/jpa-changelog-1.0.0.Final.xml");
        logEditor.mergeAddPrimeryKeyIntoCreateTable();
        String output = logEditor.toString();
        System.out.print(output);
        assertFalse(output.contains("addPrimaryKey"));
    }

    @Test
    public void testCreateIndexesForForeignKeys() throws JAXBException {
        logEditor.loadDatabaseChangeLog("/home/fratt/CloudTrust/git/keycloak-3.3.0.Final/keycloak/model/jpa/src/main/resources/META-INF/jpa-changelog-1.0.0.Final.xml");
        logEditor.createIndexesForForeignKeys();
        String output = logEditor.toString();
        System.out.print(output);
        assertTrue(output.contains("createIndex"));
        assertTrue(output.lastIndexOf("createTable") < output.indexOf("createIndex"));
        assertTrue(output.contains("addForeignKeyConstraint"));
        assertTrue(output.lastIndexOf("createIndex") < output.indexOf("addForeignKeyConstraint"));
        assertTrue(output.lastIndexOf("_foreign") < output.indexOf("addForeignKeyConstraint"));
        assertTrue(output.lastIndexOf("_index") < output.indexOf("createIndex"));
    }

    @Test
    public void testChangeModifyDataTypeToRecreateColumn() throws  JAXBException, IOException {
        logEditor.loadDatabaseChangeLog("/home/fratt/CloudTrust/git/keycloak-3.3.0.Final/keycloak/model/jpa/src/main/resources/META-INF/jpa-changelog-1.7.0.xml");
        String output = logEditor.toString();
        assertTrue(output.contains("modifyDataType"));
        logEditor.changeModifyDataTypeToRecreateColumn();
        output = logEditor.toString();
        System.out.print(output);
        assertFalse(output.contains("modifyDataType"));
        assertTrue(output.contains("addColumn"));
        assertTrue(output.lastIndexOf("addColumn") < output.lastIndexOf("<sql>"));
        assertTrue(output.lastIndexOf("<sql>") < output.lastIndexOf("dropColumn"));
        assertTrue(output.lastIndexOf("dropColumn") < output.lastIndexOf("renameColumn"));

    }

    @Test
    public void testChangeDropUniqueConstraintToDropIndex() throws JAXBException {
        logEditor.loadDatabaseChangeLog("/home/fratt/CloudTrust/git/keycloak-3.3.0.Final/keycloak/model/jpa/src/main/resources/META-INF/jpa-changelog-1.2.0.Beta1.xml");
        String output = logEditor.toString();
        assertTrue(output.contains("dropUniqueConstraint"));
        logEditor.changeDropUniqueConstraintToDropIndex();
        output = logEditor.toString();
        System.out.print(output);
        assertFalse(output.contains("dropUniqueConstraint"));
        assertTrue(output.contains("dropIndex"));
    }

    @Test
    public void testPrintToFile() throws JAXBException, IOException {
        logEditor.loadDatabaseChangeLog("/home/fratt/CloudTrust/git/keycloak-3.3.0.Final/keycloak/model/jpa/src/main/resources/META-INF/jpa-changelog-1.0.0.Final.xml");
        logEditor.mergeAddPrimeryKeyIntoCreateTable();
        logEditor.printToFile();
        assertTrue(new File("/home/fratt/CloudTrust/git/keycloak-3.3.0.Final/keycloak/model/jpa/src/main/resources/META-INF/jpa-changelog-1.0.0.Final-cockroachdb.xml").exists());
    }
}
