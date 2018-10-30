/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package clusterer;

import indexer.WMTIndexer;
import static indexer.WMTIndexer.FIELD_ANALYZED_CONTENT;
import static indexer.WMTIndexer.FIELD_DOMAIN_ID;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.util.BytesRef;

/**
 *
 * @author dganguly
 */
public class FPACWithMCentroids extends LuceneClusterer {
    IndexSearcher searcher;
    RelatedDocumentsRetriever[] rdes;
    
    
    //Estos son los grupos de centroides
    RelatedDocumentsRetriever[][] CentroidsGroups;
    
    // Un conjunto de términos para cada cluster
    
    Set<String>[] listSetOfTermsForEachCluster;
    
    public FPACWithMCentroids(String propFile) throws Exception {
        super(propFile);
        
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());        
        rdes = new RelatedDocumentsRetriever[K];
        listSetOfTermsForEachCluster = new HashSet[K];
        
        // Inicia estructura que guarda los centroides
        CentroidsGroups = new RelatedDocumentsRetriever[K][numberOfCentroidsByGroup];                
    }
    
    int selectDoc(HashSet<String> queryTerms) throws IOException {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String qterm : queryTerms) {
            TermQuery tq = new TermQuery(new Term(contentFieldName, qterm));
            b.add(new BooleanClause(tq, BooleanClause.Occur.MUST_NOT));
        }
        
        TopDocsCollector collector = TopScoreDocCollector.create(1);
        searcher.search(b.build(), collector);
        TopDocs topDocs = collector.topDocs();
        return topDocs.scoreDocs == null || topDocs.scoreDocs.length == 0? -1 :
                topDocs.scoreDocs[0].doc;
    }
    
    // Initialize centroids
    // The idea is to select a random document. Grow a region around it and choose
    // as the next candidate centroid a document that does not belong to this region.
    // Same but with several centroids for each cluster
    // With the top list we select the most similar docs from an initial selected doc
    // at each iteration
    @Override
    void initCentroids() throws Exception {
        int selectedDoc = (int)(Math.random()*numDocs);
        int numClusterCentresAssigned = 1;
        centroidDocIds = new HashMap<>();
        int idxCentroidsGroup = 0;
        
        do {
            idxCentroidsGroup = 0;
            RelatedDocumentsRetriever rde = new RelatedDocumentsRetriever(reader, selectedDoc, prop, numClusterCentresAssigned);
            TopDocs topDocs = rde.getRelatedDocs(numberOfCentroidsByGroup);
            if (topDocs == null || topDocs.scoreDocs.length < numberOfCentroidsByGroup) {
                selectedDoc = rde.getUnrelatedDocument(centroidDocIds);
                continue;
            }
            for (ScoreDoc docFromTopDocs : topDocs.scoreDocs) {
                centroidDocIds.put(docFromTopDocs.doc, null);
                CentroidsGroups[numClusterCentresAssigned - 1][idxCentroidsGroup++] = new RelatedDocumentsRetriever(reader, 
                        docFromTopDocs.doc, prop, numClusterCentresAssigned);
//                System.out.println("Chosen doc " + docFromTopDocs.doc + " as centroid number " + numClusterCentresAssigned);
            }        
            selectedDoc = rde.getUnrelatedDocument(centroidDocIds);
            numClusterCentresAssigned++;
        } while (numClusterCentresAssigned <= K);        
    }
    
    @Override
    void showCentroids() throws Exception {
        for (int i = 0, j = 1; i < K; i++) {
            System.out.println("Cluster " +  (i + 1) + " has the centroids:");
            for (RelatedDocumentsRetriever rde: CentroidsGroups[i]) {
                Document doc = rde.queryDoc;
                System.out.println("Centroid " + ((j++) % (numberOfCentroidsByGroup + 1)) + ": " + doc.get(WMTIndexer.FIELD_DOMAIN_ID) + ", " + doc.get(idFieldName));
            }
        }
    }
    
    @Override
    boolean isCentroid(int docId) {
        for (int i=0; i < K; i++) {
            for (RelatedDocumentsRetriever rde : CentroidsGroups[i]) {
                if (rde.docId == docId)
                    return true;
            }
        }
        return false;
    }
    
    @Override
    int getClosestCluster(int docId) throws Exception { // O(K) computation...
        float maxScore = 0;
        int clusterId = 0;
        float localScore = 0;
        for (int i=0; i < K; i++) {
            localScore = 0;
            for (RelatedDocumentsRetriever rde : CentroidsGroups[i]) {
                if (rde.docScoreMap == null)
                    continue;
                ScoreDoc sd = rdes[i].docScoreMap.get(docId);
                if (sd != null)
                    localScore +=  sd.score;
            }
            if (localScore > maxScore) {
                maxScore = localScore;
                clusterId = i;
            }
        }
        if (maxScore == 0) {
            // Retrieved in none... Assign to a random cluster id
            clusterId = (int)(Math.random()*K);
        }
        return clusterId;
    }
    
    // Returns true if the cluster id is changed...
    @Override
    boolean assignClusterId(int docId, int clusterId) throws Exception {
//        CentroidsGroups[clusterId][0].addDocId(docId);        
        return super.assignClusterId(docId, clusterId);
    }
        
    
    ArrayList<ArrayList<Integer>> ListOfDocsForEachCluster() throws Exception {
        int clusterId = 0;
        ArrayList<ArrayList<Integer>> docsIdForThisCluster = new ArrayList<>(K);
        for (int i = 0; i < numDocs; i++)
            docsIdForThisCluster.get(getClusterId(i)).add(i);
        return docsIdForThisCluster;
    }
    
    @Override
    void recomputeCentroids() throws Exception {
        int newCentroidDocId;
        ArrayList<HashSet<String>> clustersVocabulary = new ArrayList<>();
        ArrayList<ArrayList<Integer>> docsInEachCluster = new ArrayList<>(K);
        
        int clusterId;
        Terms tfvector;
        TermsEnum termsEnum;
        BytesRef term;
        
        //Por cada Cluster
        for (int i = 0; i < K; i++) {
            clustersVocabulary.add(new HashSet<>());
            docsInEachCluster.add(new ArrayList<>());
        }
        
        // Por cada documento
        for (int docId = 0; docId < numDocs; docId++) {
            clusterId = getClusterId(docId);
            docsInEachCluster.get(getClusterId(docId)).add(docId);
            tfvector = reader.getTermVector(docId, contentFieldName);
            if (tfvector == null || tfvector.size() == 0)
                continue;
            termsEnum = tfvector.iterator();
            while ((term = termsEnum.next()) != null) { // explore the terms for this field
                clustersVocabulary.get(clusterId).add(term.utf8ToString());
            }
        }
        
        // Por cada cluster
        for (int cluster = 0; cluster < K; cluster++) {
            System.out.println("Calculando en cluster " + cluster);
            HashSet<String> clusterVocabulary = clustersVocabulary.get(cluster);
            int idx = 0;
            Set<String> intersection = new HashSet<>();
            Set<String> bestDoc = new HashSet<>();
            int maxCover = 0;
            int bestDocId = 0;
            while (!clusterVocabulary.isEmpty() && idx < numberOfCentroidsByGroup) {
                System.out.println("Calculando en centroide " + (idx));
                maxCover = 0;
                // Por cada documento en este cluster
                for (int docId = 0; docId < docsInEachCluster.get(cluster).size(); docId++) {
                    Set<String> docVocabulary = new HashSet<>();
                    tfvector = reader.getTermVector(docId, contentFieldName);
                    if (tfvector == null || tfvector.size() == 0)
                        continue;
                    termsEnum = tfvector.iterator();
                    while ((term = termsEnum.next()) != null) { // explore the terms for this field
                        docVocabulary.add(term.utf8ToString());
                    }
                    intersection = new HashSet<>(docVocabulary); // use the copy constructor
                    intersection.retainAll(clusterVocabulary);
                    if (intersection.size() > maxCover) {
                        maxCover = intersection.size();
                        bestDoc = intersection;
                        bestDocId = docId;
                    }
                }
                clusterVocabulary.removeAll(bestDoc);
                CentroidsGroups[cluster][idx++] = new RelatedDocumentsRetriever(reader, bestDocId, prop, cluster + 1);
            }
        }
        
    }
    
    public static void main(String[] args) {
        float changeRatio = 0;
        if (args.length == 0) {
            args = new String[1];
            System.out.println("Usage: java FastKMedoidsClusterer <prop-file>");
            args[0] = "init.properties";
        }
        
        try {
            LuceneClusterer fkmc = new FPACWithMCentroids(args[0]);
            fkmc.cluster();
//            fkmc.resetAllClusterIds();
//            fkmc.initCentroids();
//            fkmc.showCentroids();
//            System.out.println("Reassigning cluster ids to non-centroid docs...");
//            changeRatio = fkmc.assignClusterIds();
//            
//            System.out.println(changeRatio + " fraction of the documents reassigned different clusters...");
//            fkmc.recomputeCentroids();
//            fkmc.showCentroids();
            boolean eval = Boolean.parseBoolean(fkmc.getProperties().getProperty("eval", "true"));
            if (eval) {
                ClusterEvaluator ceval = new ClusterEvaluator(args[0]);
                System.out.println("Purity: " + ceval.computePurity());
                System.out.println("NMI: " + ceval.computeNMI());            
                System.out.println("RI: " + ceval.computeRandIndex());            
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
